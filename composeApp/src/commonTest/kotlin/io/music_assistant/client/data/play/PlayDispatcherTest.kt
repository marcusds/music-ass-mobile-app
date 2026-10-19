package io.music_assistant.client.data.play

import io.music_assistant.client.api.Answer
import io.music_assistant.client.api.ConnectionInfo
import io.music_assistant.client.api.Request
import io.music_assistant.client.data.model.client.QueueOption
import io.music_assistant.client.data.model.server.ServerInfo
import io.music_assistant.client.data.model.server.StubServiceClient
import io.music_assistant.client.utils.ConnectionData
import io.music_assistant.client.utils.SessionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class PlayDispatcherTest {
    @Test
    fun `ten different taps during a running request send two requests`() = runTest {
        val client = GatedClient()
        val dispatcher = PlayDispatcher(client, backgroundScope)

        dispatcher.play(play("0"))
        runCurrent()
        (1..9).forEach { dispatcher.play(play("$it")) }
        runCurrent()
        client.answer()
        runCurrent()
        client.answer()
        runCurrent()

        assertEquals(listOf("0", "9"), client.sentMedia)
        assertEquals(emptySet(), dispatcher.busyQueueIds.value)
    }

    @Test
    fun `a repeat of the running request is not sent`() = runTest {
        val client = GatedClient()
        val dispatcher = PlayDispatcher(client, backgroundScope)

        dispatcher.play(play("a"))
        runCurrent()
        dispatcher.play(play("a"))
        client.answer()
        runCurrent()

        assertEquals(listOf("a"), client.sentMedia)
    }

    @Test
    fun `a failed request does not stop the next one`() = runTest {
        val client = GatedClient()
        val dispatcher = PlayDispatcher(client, backgroundScope)

        dispatcher.play(play("a"))
        dispatcher.play(play("add-b", QueueOption.ADD))
        runCurrent()
        client.answer(Result.failure(IllegalStateException("boom")))
        runCurrent()

        assertEquals(listOf("a", "add-b"), client.sentMedia)
    }

    @Test
    fun `a queue is busy from the tap until its last answer even a failed one`() = runTest {
        val client = GatedClient()
        val dispatcher = PlayDispatcher(client, backgroundScope)

        dispatcher.play(play("a"))
        runCurrent()
        assertEquals(setOf(QUEUE), dispatcher.busyQueueIds.value)

        // RpcEngine.failAll answers every open request this way when the connection drops.
        client.answer(Result.failure(IllegalStateException("connection lost")))
        runCurrent()
        assertEquals(emptySet(), dispatcher.busyQueueIds.value)
    }

    @Test
    fun `a tap after the queue went idle starts a new request`() = runTest {
        val client = GatedClient()
        val dispatcher = PlayDispatcher(client, backgroundScope)

        dispatcher.play(play("a"))
        runCurrent()
        client.answer()
        runCurrent()
        dispatcher.play(play("a"))
        runCurrent()

        assertEquals(listOf("a", "a"), client.sentMedia)
    }

    @Test
    fun `queues do not wait for each other`() = runTest {
        val client = GatedClient()
        val dispatcher = PlayDispatcher(client, backgroundScope)

        dispatcher.play(play("a", queue = "kitchen"))
        dispatcher.play(play("b", queue = "office"))
        runCurrent()

        assertEquals(listOf("a", "b"), client.sentMedia)
        assertEquals(setOf("kitchen", "office"), dispatcher.busyQueueIds.value)
    }

    @Test
    fun `an endless mix uses the wire of the server the session is on`() = runTest {
        val client = GatedClient()
        val dispatcher = PlayDispatcher(client, backgroundScope)

        client.sessionState.value = connectedTo(schemaVersion = 31)
        dispatcher.play(play("library://track/1", QueueOption.REPLACE, endlessMix = true))
        runCurrent()
        client.answer()
        runCurrent()
        client.sessionState.value = connectedTo(schemaVersion = 65)
        dispatcher.play(play("library://track/1", QueueOption.REPLACE, endlessMix = true))
        runCurrent()

        assertEquals(
            listOf("library://track/1", "radio_playlist://playlist/library://track/1"),
            client.sentMedia,
        )
        assertEquals(listOf(true, false), client.sentRadioMode)
    }

    /** Holds every request until the test answers it, oldest first. */
    private class GatedClient : StubServiceClient() {
        override val sessionState = MutableStateFlow<SessionState>(SessionState.Disconnected.Initial)
        private val sent = mutableListOf<Request>()

        val sentRadioMode: List<Boolean>
            get() = sent.map { it.args?.get("radio_mode")?.jsonPrimitive?.boolean ?: false }
        private val pending = ArrayDeque<CompletableDeferred<Result<Answer>>>()

        val sentMedia: List<String>
            get() = sent.map { request ->
                request.args?.get("media")?.jsonArray?.single()?.jsonPrimitive?.content.orEmpty()
            }

        override suspend fun sendRequest(request: Request): Result<Answer> {
            sent += request
            return CompletableDeferred<Result<Answer>>().also { pending.addLast(it) }.await()
        }

        fun answer(result: Result<Answer> = Result.success(emptyAnswer())) {
            pending.removeFirst().complete(result)
        }
    }

    private companion object {
        const val QUEUE = "queue"

        fun play(
            media: String,
            option: QueueOption = QueueOption.PLAY,
            queue: String = QUEUE,
            endlessMix: Boolean = false,
        ) = PlayRequest(queueOrPlayerId = queue, media = listOf(media), option = option, endlessMixMode = endlessMix)

        fun connectedTo(schemaVersion: Int) = SessionState.Connected.Direct(
            connectionInfo = ConnectionInfo(host = "ma.local", port = 8095, isTls = false),
            connectionData = ConnectionData(serverInfo = ServerInfo(serverId = "s", schemaVersion = schemaVersion)),
        )

        fun emptyAnswer() = Answer(json = buildJsonObject { put("message_id", JsonPrimitive("fake")) })
    }
}

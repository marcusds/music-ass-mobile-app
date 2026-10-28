package io.music_assistant.client.data.repository

import io.music_assistant.client.api.Answer
import io.music_assistant.client.api.Request
import io.music_assistant.client.data.factory.MediaItemFactory
import io.music_assistant.client.data.model.client.MediaType
import io.music_assistant.client.data.model.server.StubServiceClient
import io.music_assistant.client.data.model.server.events.Event
import io.music_assistant.client.utils.myJson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Callers sit on the main thread, so the repository must decode and map an answer on its own
 * dispatcher and not on the dispatcher of the caller.
 */
class MediaItemRepositoryDecodeTest {
    private class CountingDispatcher : CoroutineDispatcher() {
        var dispatches = 0

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatches++
            block.run()
        }
    }

    private class FakeClient(private val result: String) : StubServiceClient() {
        override val events: Flow<Event<out Any>> = emptyFlow()

        override suspend fun sendRequest(request: Request): Result<Answer> = Result.success(
            Answer(
                buildJsonObject {
                    put("message_id", request.messageId)
                    put("result", myJson.parseToJsonElement(result))
                },
            ),
        )
    }

    @Test
    fun searchDecodesOnTheDecodeDispatcher() = runTest {
        val client = FakeClient(
            """{"tracks":[{"item_id":"1","provider":"library","name":"T","media_type":"track","is_playable":true}]}""",
        )
        val dispatcher = CountingDispatcher()
        val repository = ServiceClientMediaItemRepository(client, MediaItemFactory(client), dispatcher)

        val result = repository.search(
            Request.Library.search(query = "t", mediaTypes = listOf(MediaType.TRACK), limit = 10, libraryOnly = false),
        )

        assertEquals(listOf("1"), result.getOrThrow().tracks.map { it.itemId })
        assertTrue(dispatcher.dispatches > 0, "The decode must run on the decode dispatcher")
    }

    @Test
    fun undecodablePayloadIsAFailedResult() = runTest {
        val client = FakeClient("42")
        val repository = ServiceClientMediaItemRepository(client, MediaItemFactory(client), CountingDispatcher())

        assertTrue(repository.fetchMediaItems(Request.Player.all()).isFailure)
    }
}

package io.music_assistant.client.api

import io.music_assistant.client.utils.myJson
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A request without an answer must fail after its timeout and withdraw its callback, so the
 * caller is not stuck and the engine does not keep the request. Commands that answer only
 * after playback get a longer limit.
 */
class RequestTimeoutTest {
    private fun engine() = RpcEngine(onAuthError = {}, onError = {})

    private fun message(raw: String): JsonObject = Json.parseToJsonElement(raw) as JsonObject

    @Test
    fun unansweredRequestFailsAfterTimeoutAndRemovesCallback() = runTest {
        val engine = engine()
        var sent = false
        val call = async {
            engine.awaitAnswer("m1", "music/search", 30.seconds) { sent = true }
        }
        runCurrent()
        assertTrue(sent)

        advanceTimeBy(29.seconds)
        assertFalse(call.isCompleted, "The request must wait for the whole timeout")
        advanceTimeBy(2.seconds)

        val error = call.await().exceptionOrNull()
        assertIs<RequestTimeoutException>(error)
        assertEquals("music/search", error.command)
        assertFalse(engine.removeCallback("m1"), "The timeout must withdraw the callback")
        // A late answer finds no callback and is dropped.
        assertTrue(engine.handleResponse(message("""{"message_id":"m1","result":true}""")))
    }

    @Test
    fun answerBeforeTimeoutCompletesRequest() = runTest {
        val engine = engine()
        val call = async { engine.awaitAnswer("m2", "players/all", 30.seconds) {} }
        runCurrent()

        engine.handleResponse(message("""{"message_id":"m2","result":[]}"""))

        val answer = call.await().getOrThrow()
        assertEquals("m2", answer.messageId)
    }

    @Test
    fun sendFailureFailsRequestOnce() = runTest {
        val engine = engine()
        val cause = IllegalStateException("Not connected")
        val result = engine.awaitAnswer("m3", "players/all", 30.seconds) {
            assertTrue(engine.fail("m3", cause))
        }

        assertSame(cause, result.exceptionOrNull())
        assertFalse(engine.fail("m3", cause), "A completed request cannot fail again")
    }

    @Test
    fun cancelledCallerRemovesCallback() = runTest {
        val engine = engine()
        val call = async { engine.awaitAnswer("m4", "players/all", 30.seconds) {} }
        runCurrent()

        call.cancel()
        runCurrent()

        assertFalse(engine.removeCallback("m4"))
    }

    @Test
    fun ordinaryCommandsUseTheDefaultTimeout() {
        assertEquals(DEFAULT_REQUEST_TIMEOUT, Request.Player.all().answerTimeout())
        assertEquals(DEFAULT_REQUEST_TIMEOUT, Request(command = APICommands.MUSIC_ARTISTS_LIBRARY_ITEMS).answerTimeout())
    }

    @Test
    fun commandsThatAnswerAfterPlaybackGetTheLongTimeout() {
        listOf(
            APICommands.PLAYER_QUEUES_PLAY_MEDIA,
            APICommands.PLAYER_QUEUES_PLAY_INDEX,
            APICommands.PLAYER_QUEUES_TRANSFER,
            APICommands.PLAYERS_CMD_POWER,
            APICommands.PLAYERS_CMD_SET_MEMBERS,
        ).forEach { command ->
            assertEquals(PLAYBACK_REQUEST_TIMEOUT, Request(command = command).answerTimeout(), command)
        }
        assertEquals(SEARCH_REQUEST_TIMEOUT, Request(command = APICommands.MUSIC_SEARCH).answerTimeout())
    }

    @Test
    fun requestTimeoutOverridesTheCommandDefault() {
        assertEquals(5.seconds, Request(command = APICommands.PLAYERS_ALL, timeout = 5.seconds).answerTimeout())
        assertEquals(
            ANNOUNCEMENT_REQUEST_TIMEOUT,
            Request.Player.playAnnouncement("p1", "hi", preAnnounce = null, volumeLevel = null).answerTimeout(),
        )
    }

    @Test
    fun timeoutIsNotSentToTheServer() {
        val json = myJson.encodeToJsonElement(
            Request.serializer(),
            Request(command = APICommands.PLAYERS_ALL, timeout = 5.seconds),
        ) as JsonObject

        assertFalse(json.containsKey("timeout"))
    }
}

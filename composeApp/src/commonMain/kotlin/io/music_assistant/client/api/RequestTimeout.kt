package io.music_assistant.client.api

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * No answer to [command] arrived within [timeout]. Only this request fails: the connection
 * stays up, and the ping of the socket decides whether the link is dead.
 */
class RequestTimeoutException(val command: String, val timeout: Duration) :
    Exception("No answer to $command within $timeout")

/** Default for a request that is not listed below. */
internal val DEFAULT_REQUEST_TIMEOUT = 30.seconds

/** An older server waits for every provider of a search, without its own time limit. */
internal val SEARCH_REQUEST_TIMEOUT = 60.seconds

/**
 * Player commands answer only after the player acts. `play_media` answers after playback
 * starts, which can take tens of seconds.
 */
internal val PLAYBACK_REQUEST_TIMEOUT = 120.seconds

/** The server answers an announcement after it has played, and it plays one announcement at a time. */
internal val ANNOUNCEMENT_REQUEST_TIMEOUT = 5.minutes

/**
 * For a library item, `music/refresh_item` answers only after the server has matched the other
 * providers and reloaded the metadata, which can take minutes.
 */
internal val REFRESH_ITEM_REQUEST_TIMEOUT = 10.minutes

private val playbackQueueCommands = setOf(
    APICommands.PLAYER_QUEUES_PLAY_MEDIA,
    APICommands.PLAYER_QUEUES_PLAY_INDEX,
    APICommands.PLAYER_QUEUES_TRANSFER,
)

/** How long the client waits for the answer to this request: [Request.timeout], or the default of the command. */
internal fun Request.answerTimeout(): Duration = timeout ?: when {
    command in playbackQueueCommands -> PLAYBACK_REQUEST_TIMEOUT
    command.startsWith("${APICommands.PLAYERS_CMD}/") -> PLAYBACK_REQUEST_TIMEOUT
    command == APICommands.MUSIC_SEARCH -> SEARCH_REQUEST_TIMEOUT
    else -> DEFAULT_REQUEST_TIMEOUT
}

/**
 * Registers a callback for [messageId], runs [send] and suspends until the answer arrives.
 *
 * [send] hands the request to the transport and returns without waiting for the answer. It
 * reports a send failure with [RpcEngine.fail]. When no answer arrives within [timeout], the
 * request fails with [RequestTimeoutException] and its callback is removed, so a late answer
 * is dropped. Cancellation of the caller removes the callback too.
 */
internal suspend fun RpcEngine.awaitAnswer(
    messageId: String,
    command: String,
    timeout: Duration,
    send: () -> Unit,
): Result<Answer> = withTimeoutOrNull(timeout) {
    suspendCancellableCoroutine<Result<Answer>> { continuation ->
        registerCallback(messageId) { continuation.resume(it) }
        continuation.invokeOnCancellation { removeCallback(messageId) }
        send()
    }
} ?: Result.failure(RequestTimeoutException(command, timeout))

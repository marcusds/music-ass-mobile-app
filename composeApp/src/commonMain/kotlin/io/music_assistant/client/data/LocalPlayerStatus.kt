package io.music_assistant.client.data

import io.music_assistant.client.utils.mapState
import io.music_assistant.sendspin.api.PlayerState
import kotlinx.coroutines.flow.StateFlow

/**
 * The connection state of the local player, as the UI shows it. It has no audio status: the
 * buffer level changes with every audio chunk, and the UI would rebuild many times a second.
 */
enum class LocalPlayerStatus { Connecting, Connected, Reconnecting, Failed }

/** The UI status of this state, or null while the player is disabled. */
fun PlayerState.toLocalPlayerStatus(): LocalPlayerStatus? = when (this) {
    PlayerState.Disabled -> null
    is PlayerState.Connecting -> LocalPlayerStatus.Connecting
    is PlayerState.Connected -> LocalPlayerStatus.Connected
    is PlayerState.Reconnecting -> LocalPlayerStatus.Reconnecting
    is PlayerState.Failed -> LocalPlayerStatus.Failed
}

/** [this] as UI statuses: it emits only when the status changes, never for a new buffer level. */
fun StateFlow<PlayerState?>.localPlayerStatus(): StateFlow<LocalPlayerStatus?> =
    mapState { it?.toLocalPlayerStatus() }

package io.music_assistant.client.data

import io.music_assistant.client.utils.mapState
import io.music_assistant.sendspin.api.PlayerState
import kotlinx.coroutines.flow.StateFlow

/** The local player's connection state for the UI, without the buffer level that changes with every audio chunk. */
enum class LocalPlayerStatus { Connecting, Connected, Reconnecting, Failed }

fun PlayerState.toLocalPlayerStatus(): LocalPlayerStatus? = when (this) {
    PlayerState.Disabled -> null
    is PlayerState.Connecting -> LocalPlayerStatus.Connecting
    is PlayerState.Connected -> LocalPlayerStatus.Connected
    is PlayerState.Reconnecting -> LocalPlayerStatus.Reconnecting
    is PlayerState.Failed -> LocalPlayerStatus.Failed
}

fun StateFlow<PlayerState?>.localPlayerStatus(): StateFlow<LocalPlayerStatus?> =
    mapState { it?.toLocalPlayerStatus() }

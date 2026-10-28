package io.music_assistant.client.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Watches for a network loss under a live connection and calls [onLossConfirmed] only when the
 * network stays lost for [graceMs].
 *
 * A handoff (Wi-Fi to cellular, a VPN reconnect) reports a short loss. When the network comes
 * back within the grace period, the connection is kept and its own liveness check decides
 * whether it survived.
 *
 * @param liveConnection returns the current live connection, or null when there is none. The
 *        loss is confirmed only if the same connection is still live when the grace period ends.
 * @param onLossSeen called when a loss under a live connection starts the grace period.
 */
internal fun CoroutineScope.watchNetworkLoss(
    networkAvailable: StateFlow<Boolean>,
    graceMs: Long,
    liveConnection: () -> Any?,
    onLossSeen: () -> Unit = {},
    onLossConfirmed: () -> Unit,
): Job = launch {
    var wasAvailable = networkAvailable.value
    // collectLatest cancels the grace period as soon as the network comes back.
    networkAvailable.collectLatest { available ->
        val lost = wasAvailable && !available
        wasAvailable = available
        val connection = liveConnection()?.takeIf { lost } ?: return@collectLatest
        onLossSeen()
        delay(graceMs)
        if (liveConnection() === connection) onLossConfirmed()
    }
}

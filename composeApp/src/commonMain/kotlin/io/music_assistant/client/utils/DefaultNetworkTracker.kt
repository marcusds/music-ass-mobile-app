package io.music_assistant.client.utils

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Availability of the default network, from platform callbacks that name the network.
 *
 * On a handoff the platform can report the new default network before it reports the
 * loss of the old one. Only the loss of the current default network makes the network
 * unavailable, so a late loss of a replaced network does not report an outage.
 * Call the methods from one thread, as the platform callbacks do.
 */
internal class DefaultNetworkTracker<N : Any>(initiallyAvailable: Boolean) {
    private val _isAvailable = MutableStateFlow(initiallyAvailable)
    val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()

    private var current: N? = null

    fun onAvailable(network: N) {
        current = network
        _isAvailable.value = true
    }

    fun onLost(network: N) {
        if (current?.let { it != network } == true) return
        current = null
        _isAvailable.value = false
    }
}

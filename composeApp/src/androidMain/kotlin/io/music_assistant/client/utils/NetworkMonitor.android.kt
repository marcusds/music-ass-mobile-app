@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.music_assistant.client.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import io.music_assistant.client.player.PlatformContext
import kotlinx.coroutines.flow.StateFlow

actual class NetworkMonitor actual constructor(platformContext: PlatformContext) {
    private val cm = platformContext.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val tracker = DefaultNetworkTracker<Network>(
        initiallyAvailable = cm.activeNetwork
            ?.let { cm.getNetworkCapabilities(it) }
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
    )

    actual val isAvailable: StateFlow<Boolean> = tracker.isAvailable

    init {
        // A Wi-Fi to cellular handoff can deliver onAvailable(new) before onLost(old);
        // the tracker ignores the loss of a network that is no longer the default.
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = tracker.onAvailable(network)

            override fun onLost(network: Network) = tracker.onLost(network)
        })
    }
}

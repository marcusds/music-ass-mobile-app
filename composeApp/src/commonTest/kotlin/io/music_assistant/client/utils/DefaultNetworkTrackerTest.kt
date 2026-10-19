package io.music_assistant.client.utils

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DefaultNetworkTrackerTest {
    @Test
    fun aLateLossOfTheReplacedNetworkKeepsTheNetworkAvailable() {
        val tracker = DefaultNetworkTracker<String>(initiallyAvailable = true)
        tracker.onAvailable("wifi")

        tracker.onAvailable("cellular")
        tracker.onLost("wifi")

        assertTrue(tracker.isAvailable.value)
    }

    @Test
    fun theLossOfTheCurrentNetworkMakesTheNetworkUnavailable() {
        val tracker = DefaultNetworkTracker<String>(initiallyAvailable = true)
        tracker.onAvailable("wifi")

        tracker.onLost("wifi")

        assertFalse(tracker.isAvailable.value)
    }

    @Test
    fun aNewNetworkAfterTheLossMakesTheNetworkAvailableAgain() {
        val tracker = DefaultNetworkTracker<String>(initiallyAvailable = true)
        tracker.onAvailable("wifi")
        tracker.onLost("wifi")

        tracker.onAvailable("cellular")

        assertTrue(tracker.isAvailable.value)
    }

    @Test
    fun aLossBeforeAnyCallbackMakesTheNetworkUnavailable() {
        val tracker = DefaultNetworkTracker<String>(initiallyAvailable = true)

        tracker.onLost("wifi")

        assertFalse(tracker.isAvailable.value)
    }
}

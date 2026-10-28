package io.music_assistant.client.api

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class NetworkLossWatchTest {
    private val grace = 6_000L

    @Test
    fun aLossThatOutlastsTheGracePeriodIsConfirmed() = runTest {
        val network = MutableStateFlow(true)
        val connection = Any()
        var confirmed = 0
        backgroundScope.watchNetworkLoss(network, grace, liveConnection = { connection }) { confirmed++ }
        runCurrent()

        network.value = false
        advanceTimeBy(grace - 1)
        runCurrent()
        assertEquals(0, confirmed)

        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, confirmed)
    }

    @Test
    fun aHandoffWithinTheGracePeriodKeepsTheConnection() = runTest {
        val network = MutableStateFlow(true)
        val connection = Any()
        var seen = 0
        var confirmed = 0
        backgroundScope.watchNetworkLoss(
            network,
            grace,
            liveConnection = { connection },
            onLossSeen = { seen++ },
        ) { confirmed++ }
        runCurrent()

        network.value = false
        runCurrent()
        advanceTimeBy(grace / 2)
        network.value = true
        advanceTimeBy(grace * 2)
        runCurrent()

        assertEquals(1, seen)
        assertEquals(0, confirmed)
    }

    @Test
    fun aConnectionReplacedDuringTheGracePeriodIsNotTornDown() = runTest {
        val network = MutableStateFlow(true)
        var connection: Any? = Any()
        var confirmed = 0
        backgroundScope.watchNetworkLoss(network, grace, liveConnection = { connection }) { confirmed++ }
        runCurrent()

        network.value = false
        runCurrent()
        connection = Any()
        advanceTimeBy(grace + 1)
        runCurrent()

        assertEquals(0, confirmed)
    }

    @Test
    fun aLossWithoutALiveConnectionIsIgnored() = runTest {
        val network = MutableStateFlow(true)
        var confirmed = 0
        backgroundScope.watchNetworkLoss(network, grace, liveConnection = { null }) { confirmed++ }
        runCurrent()

        network.value = false
        advanceTimeBy(grace + 1)
        runCurrent()

        assertEquals(0, confirmed)
    }

    @Test
    fun aNetworkThatStartsLostIsNotALoss() = runTest {
        val network = MutableStateFlow(false)
        val connection = Any()
        var confirmed = 0
        backgroundScope.watchNetworkLoss(network, grace, liveConnection = { connection }) { confirmed++ }
        advanceTimeBy(grace + 1)
        runCurrent()

        assertEquals(0, confirmed)
    }
}

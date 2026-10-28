package io.music_assistant.client.data

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A Sendspin reconnect over WebRTC must not restart the API session while the peer
 * connection can carry a new channel, and must still fall back when it cannot.
 */
class SendspinChannelPlanTest {
    private val plan = SendspinChannelPlan()

    @Test
    fun theNegotiatedChannelComesFirst() {
        assertEquals(SendspinChannelSource.NEGOTIATED, plan.next(negotiatedUnused = true, inBandSupported = true))
        assertEquals(SendspinChannelSource.NEGOTIATED, plan.next(negotiatedUnused = true, inBandSupported = false))
    }

    @Test
    fun aSpentChannelIsReplacedInBand() {
        assertEquals(SendspinChannelSource.IN_BAND, plan.next(negotiatedUnused = false, inBandSupported = true))
    }

    @Test
    fun anOldGatewayRenegotiates() {
        assertEquals(SendspinChannelSource.RENEGOTIATE, plan.next(negotiatedUnused = false, inBandSupported = false))
    }

    @Test
    fun inBandChannelsThatCarryFramesKeepInBand() {
        repeat(5) {
            assertEquals(SendspinChannelSource.IN_BAND, plan.next(negotiatedUnused = false, inBandSupported = true))
            plan.onInBandChannelEnded(receivedFrames = true)
        }
        assertEquals(SendspinChannelSource.IN_BAND, plan.next(negotiatedUnused = false, inBandSupported = true))
    }

    @Test
    fun silentInBandChannelsFallBackToRenegotiation() {
        repeat(SendspinChannelPlan.MAX_SILENT_IN_BAND) {
            assertEquals(SendspinChannelSource.IN_BAND, plan.next(negotiatedUnused = false, inBandSupported = true))
            plan.onInBandChannelEnded(receivedFrames = false)
        }
        assertEquals(SendspinChannelSource.RENEGOTIATE, plan.next(negotiatedUnused = false, inBandSupported = true))

        plan.onRenegotiated()
        assertEquals(SendspinChannelSource.IN_BAND, plan.next(negotiatedUnused = false, inBandSupported = true))
    }

    @Test
    fun aChannelWithFramesResetsTheSilentStreak() {
        plan.onInBandChannelEnded(receivedFrames = false)
        plan.onInBandChannelEnded(receivedFrames = true)
        plan.onInBandChannelEnded(receivedFrames = false)
        assertEquals(SendspinChannelSource.IN_BAND, plan.next(negotiatedUnused = false, inBandSupported = true))
    }
}

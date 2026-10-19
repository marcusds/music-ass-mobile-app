package io.music_assistant.client.data

/**
 * Server schema from which the remote-access gateway routes data channels by label and serves
 * each label on its own (music-assistant/server#5635, MA 2.10, the same gate as the image
 * channel). Such a gateway accepts a new `sendspin` channel on a live peer connection once
 * the previous one has closed. An older gateway shares one Sendspin bridge per session, and
 * the teardown of the old channel can close the new one.
 */
internal const val IN_BAND_SENDSPIN_SCHEMA = 49

/** Where the next Sendspin connection over WebRTC gets its data channel. */
internal enum class SendspinChannelSource {
    /** The unused channel that was negotiated with the peer connection. */
    NEGOTIATED,

    /** A new channel opened on the live peer connection. The API session stays up. */
    IN_BAND,

    /** A new peer connection, which also restarts the API session. */
    RENEGOTIATE,
}

/**
 * Picks the data channel for each Sendspin connection over WebRTC. A channel serves one
 * connection. The negotiated channel comes first. After it, a channel opened in-band replaces
 * it, so a Sendspin reconnect does not restart the API session. A full renegotiation is the
 * fallback when the server cannot route the label, or when [MAX_SILENT_IN_BAND] in-band
 * channels in a row closed before the server sent anything on them.
 */
internal class SendspinChannelPlan {
    private var silentInBandStreak = 0

    fun next(negotiatedUnused: Boolean, inBandSupported: Boolean): SendspinChannelSource = when {
        negotiatedUnused -> SendspinChannelSource.NEGOTIATED
        inBandSupported && silentInBandStreak < MAX_SILENT_IN_BAND -> SendspinChannelSource.IN_BAND
        else -> SendspinChannelSource.RENEGOTIATE
    }

    /** Reports how the last in-band channel ended. A channel that got no frame counts against in-band. */
    fun onInBandChannelEnded(receivedFrames: Boolean) {
        silentInBandStreak = if (receivedFrames) 0 else silentInBandStreak + 1
    }

    /** A new peer connection brings a new negotiated channel, so in-band gets a new chance after it. */
    fun onRenegotiated() {
        silentInBandStreak = 0
    }

    companion object {
        const val MAX_SILENT_IN_BAND = 2
    }
}

package io.music_assistant.client.data

import io.music_assistant.sendspin.api.AudioPhase
import io.music_assistant.sendspin.api.AudioStatus
import io.music_assistant.sendspin.api.ClockQuality
import io.music_assistant.sendspin.api.FailureCause
import io.music_assistant.sendspin.api.PlayerState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The player publishes a new state after each audio chunk, with a new buffer level. The UI
 * status must not change with it, or the player UI rebuilds many times a second.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalPlayerStatusTest {
    private fun connected(bufferedMs: Int) = PlayerState.Connected(
        playerId = "phone",
        serverName = "MA",
        clock = ClockQuality.Good,
        audio = AudioStatus(AudioPhase.Playing, bufferedMs = bufferedMs, starved = false),
    )

    private fun reconnecting(bufferedMs: Int) = PlayerState.Reconnecting(
        attempt = 1,
        nextRetryAtMs = null,
        audio = AudioStatus(AudioPhase.Playing, bufferedMs = bufferedMs, starved = false),
    )

    @Test
    fun `each player state maps to its status`() {
        assertNull(PlayerState.Disabled.toLocalPlayerStatus())
        assertEquals(LocalPlayerStatus.Connecting, PlayerState.Connecting(0).toLocalPlayerStatus())
        assertEquals(LocalPlayerStatus.Connected, connected(500).toLocalPlayerStatus())
        assertEquals(LocalPlayerStatus.Reconnecting, reconnecting(500).toLocalPlayerStatus())
        assertEquals(
            LocalPlayerStatus.Failed,
            PlayerState.Failed(FailureCause.Unauthorized).toLocalPlayerStatus(),
        )
    }

    @Test
    fun `a new buffer level does not emit a new status`() = runTest {
        val source = MutableStateFlow<PlayerState?>(PlayerState.Connecting(0))
        val status = source.localPlayerStatus()
        val seen = mutableListOf<LocalPlayerStatus?>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { status.toList(seen) }

        source.value = connected(100)
        source.value = connected(140)
        source.value = connected(80)
        source.value = reconnecting(60)
        source.value = reconnecting(20)
        source.value = connected(0)
        source.value = null

        assertEquals(
            listOf(
                LocalPlayerStatus.Connecting,
                LocalPlayerStatus.Connected,
                LocalPlayerStatus.Reconnecting,
                LocalPlayerStatus.Connected,
                null,
            ),
            seen,
        )
        assertNull(status.value)
        job.cancel()
    }
}

package io.music_assistant.client.data

import com.russhwolf.settings.MapSettings
import io.music_assistant.client.api.APICommands
import io.music_assistant.client.api.Answer
import io.music_assistant.client.api.ErrorMessageBus
import io.music_assistant.client.api.Request
import io.music_assistant.client.data.model.client.Player
import io.music_assistant.client.data.model.client.QueueInfo
import io.music_assistant.client.data.model.client.RepeatMode
import io.music_assistant.client.data.model.server.StubServiceClient
import io.music_assistant.client.settings.SettingsRepository
import io.music_assistant.client.ui.compose.common.action.PlayerAction
import io.music_assistant.client.utils.SessionState
import io.music_assistant.sendspin.api.AudioPhase
import io.music_assistant.sendspin.api.AudioStatus
import io.music_assistant.sendspin.api.ClockQuality
import io.music_assistant.sendspin.api.PlayerEvent
import io.music_assistant.sendspin.api.PlayerState
import io.music_assistant.sendspin.api.SendspinPlayer
import io.music_assistant.sendspin.api.StopCause
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The adapter against a fake Sendspin player: what a local pause does to the hold,
 * to the server, and to the session keep-alive ([LocalPlayerAdapter.wantsSession]).
 * The adapter runs on its own IO scope, so the tests wait on state in real time.
 */
class LocalPlayerAdapterTest {
    private val client = RecordingClient()
    private val settings = SettingsRepository(MapSettings(), MapSettings()).apply { setSendspinEnabled(true) }
    private val player = FakePlayer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tracker = PlayerPositionTracker()
    private val adapter = run {
        val preferences = UserPreferences()
        LocalPlayerAdapter(
            settings = settings,
            apiClient = client,
            playerRequestFactory = PlayerRequestFactory(tracker, preferences),
            positionTracker = tracker,
            userPreferences = preferences,
            errorBus = ErrorMessageBus(),
            endpoints = LocalPlayerEndpoints(client, settings, scope),
            playerFactory = { _, _, _ -> player },
        )
    }

    @AfterTest
    fun tearDown() {
        adapter.cancel()
        scope.cancel()
    }

    @Test
    fun starvationPausesLikeTheUserAndKeepsTheSessionWanted() = runBlocking<Unit> {
        playing()
        player.emit(PlayerEvent.PlaybackStopped(StopCause.Starved))

        assertEquals(PAUSE, receiveCommand().command)
        await { player.held.value.takeIf { it } }
        assertFalse(adapter.localPlayerData.value?.player?.isPlaying == true)
        assertTrue(adapter.wantsSession.value)
    }

    @Test
    fun theServerConfirmingThePauseReleasesTheHoldAndTheSession() = runBlocking<Unit> {
        val local = playing()
        adapter.handleLocalCommand(requireNotNull(adapter.localPlayerData.value), PlayerAction.Pause)
        assertEquals(PAUSE, receiveCommand().command)
        assertTrue(player.held.value)

        adapter.onServerPlayerUpdate(local.copy(isPlaying = false))

        await { player.held.value.takeUnless { it } }
        await { adapter.wantsSession.value.takeUnless { it } }
        assertFalse(adapter.wantsSession.value)
    }

    @Test
    fun theServerConfirmingAStarvedPauseReleasesTheHoldButKeepsTheSessionForTheResume() = runBlocking<Unit> {
        val local = playing()
        linkDown()
        player.emit(PlayerEvent.PlaybackStopped(StopCause.Starved))
        assertEquals(PAUSE, receiveCommand().command)

        adapter.onServerPlayerUpdate(local.copy(isPlaying = false))

        await { player.held.value.takeUnless { it } }
        // The resume after the outage still needs the session.
        assertTrue(adapter.wantsSession.value)

        // The hold is gone, so the resume is a plain play.
        linkUp()
        assertEquals(PLAY, receiveCommand().command)
    }

    @Test
    fun aUserPauseAfterAConfirmedStarvedPauseReleasesTheSession() = runBlocking<Unit> {
        val local = playing()
        linkDown()
        player.emit(PlayerEvent.PlaybackStopped(StopCause.Starved))
        receiveCommand()
        adapter.onServerPlayerUpdate(local.copy(isPlaying = false))
        await { player.held.value.takeUnless { it } }

        adapter.handleLocalCommand(requireNotNull(adapter.localPlayerData.value), PlayerAction.Pause)
        assertEquals(PAUSE, receiveCommand().command)
        adapter.onServerPlayerUpdate(local.copy(isPlaying = false))

        await { adapter.wantsSession.value.takeUnless { it } }
        assertFalse(adapter.wantsSession.value)
        linkUp()
        delay(SETTLE_MS)
        assertTrue(client.sent.tryReceive().isFailure)
    }

    @Test
    fun starvationWhileAlreadyPausedChangesNothing() = runBlocking<Unit> {
        adapter.onInitialPlayersReceived(hasLocalPlayer = false)
        await { adapter.localPlayerData.value }

        player.emit(PlayerEvent.PlaybackStopped(StopCause.Starved))
        delay(SETTLE_MS)

        assertTrue(client.sent.tryReceive().isFailure)
        assertFalse(player.held.value)
        assertFalse(adapter.wantsSession.value)
    }

    @Test
    fun aStreamStartedWhileHeldIsAnsweredWithAnotherPause() = runBlocking<Unit> {
        val local = playing()
        player.emit(PlayerEvent.PlaybackStopped(StopCause.Starved))
        receiveCommand()

        player.emit(PlayerEvent.StreamStartedWhileHeld(local.id))

        assertEquals(PAUSE, receiveCommand().command)
        assertTrue(player.held.value)
    }

    @Test
    fun anOfflinePauseAsksForRecoveryAndKeepsTheSessionWantedUntilItLands() = runBlocking<Unit> {
        val local = playing()
        client.isReadyForCommands.value = false

        adapter.handleLocalCommand(requireNotNull(adapter.localPlayerData.value), PlayerAction.Pause)

        await { client.recoveries.value.takeIf { it > 0 } }
        assertTrue(client.sent.tryReceive().isFailure)
        assertTrue(adapter.wantsSession.value)

        client.isReadyForCommands.value = true
        assertEquals(PAUSE, receiveCommand().command)
        adapter.onServerPlayerUpdate(local.copy(isPlaying = false))
        await { adapter.wantsSession.value.takeUnless { it } }
        assertFalse(adapter.wantsSession.value)
    }

    @Test
    fun clearingTheStateDropsTheSessionWantButKeepsTheHold() = runBlocking<Unit> {
        playing()
        player.emit(PlayerEvent.PlaybackStopped(StopCause.Starved))
        receiveCommand()

        adapter.clearState()

        await { adapter.wantsSession.value.takeUnless { it } }
        assertTrue(player.held.value)
    }

    @Test
    fun theAudioLinkComingBackAfterAStarvedStopResumesPlayback() = runBlocking<Unit> {
        playing()
        linkDown()
        player.emit(PlayerEvent.PlaybackStopped(StopCause.Starved))
        assertEquals(PAUSE, receiveCommand().command)
        assertTrue(adapter.wantsSession.value)

        linkUp()

        // Still held (the server has not confirmed the pause): pause first, then play.
        assertEquals(PAUSE, receiveCommand().command)
        assertEquals(PLAY, receiveCommand().command)
        assertFalse(player.held.value)
    }

    @Test
    fun aUserPauseDuringTheOutageCancelsTheResume() = runBlocking<Unit> {
        playing()
        linkDown()
        player.emit(PlayerEvent.PlaybackStopped(StopCause.Starved))
        receiveCommand()

        adapter.handleLocalCommand(requireNotNull(adapter.localPlayerData.value), PlayerAction.Pause)
        assertEquals(PAUSE, receiveCommand().command)
        linkUp()
        delay(SETTLE_MS)

        assertTrue(client.sent.tryReceive().isFailure)
        assertTrue(player.held.value)
    }

    @Test
    fun aStaleConnectedStateDoesNotResumeAtOnce() = runBlocking<Unit> {
        playing()
        linkUp()
        player.emit(PlayerEvent.PlaybackStopped(StopCause.Starved))
        assertEquals(PAUSE, receiveCommand().command)
        delay(SETTLE_MS)

        assertTrue(client.sent.tryReceive().isFailure)

        linkDown()
        delay(SETTLE_MS) // a StateFlow conflates: let the adapter see the outage
        linkUp()
        assertEquals(PAUSE, receiveCommand().command)
        assertEquals(PLAY, receiveCommand().command)
    }

    @Test
    fun aFocusLossDuringTheOutageCancelsTheResume() = runBlocking<Unit> {
        playing()
        linkDown()
        player.emit(PlayerEvent.PlaybackStopped(StopCause.Starved))
        receiveCommand()

        player.emit(PlayerEvent.PlaybackStopped(StopCause.FocusLost))
        delay(SETTLE_MS)
        linkUp()
        delay(SETTLE_MS)

        assertTrue(client.sent.tryReceive().isFailure)
    }

    @Test
    fun aNextOnTheLastItemKeepsThePosition() = runBlocking<Unit> {
        playingAt(currentIndex = 0, itemCount = 1)

        adapter.handleLocalCommand(requireNotNull(adapter.localPlayerData.value), PlayerAction.Next)

        assertEquals(NEXT, receiveCommand().command)
        assertFalse(tracker.isFrozenUntilConfirmed(QUEUE_ID))
        assertEquals(EPISODE_POSITION_SEC, tracker.effectiveSec(QUEUE_ID))
    }

    @Test
    fun aNextWithAnItemAfterItFreezesAtTheStart() = runBlocking<Unit> {
        playingAt(currentIndex = 0, itemCount = 2)

        adapter.handleLocalCommand(requireNotNull(adapter.localPlayerData.value), PlayerAction.Next)

        assertEquals(NEXT, receiveCommand().command)
        assertTrue(tracker.isFrozenUntilConfirmed(QUEUE_ID))
        assertEquals(0.0, tracker.effectiveSec(QUEUE_ID))
    }

    /** The local player plays item [currentIndex] of [itemCount], paused in the tracker at a known position. */
    private suspend fun playingAt(currentIndex: Int, itemCount: Int) {
        playing()
        adapter.onServerQueueUpdate(
            QueueInfo(
                id = QUEUE_ID,
                available = true,
                currentIndex = currentIndex,
                shuffleEnabled = false,
                repeatMode = RepeatMode.OFF,
                autoPlayEnabled = false,
                elapsedTime = EPISODE_POSITION_SEC,
                elapsedTimeLastUpdated = null,
                currentItem = null,
                radioSource = emptyList(),
                itemCount = itemCount,
            ),
        )
        tracker.setAnchor(QUEUE_ID, elapsedSec = EPISODE_POSITION_SEC, isPlaying = false)
    }

    private fun linkDown() {
        player.state.value = PlayerState.Reconnecting(attempt = 1, nextRetryAtMs = null, audio = AudioStatus.IDLE)
    }

    private fun linkUp() {
        player.state.value = PlayerState.Connected(
            playerId = "local",
            serverName = "server",
            clock = ClockQuality.Good,
            audio = AudioStatus(AudioPhase.Idle, bufferedMs = 0, starved = false),
        )
    }

    /** The next command sent to the server; fails instead of hanging when none comes. */
    private suspend fun receiveCommand() = withTimeout(TIMEOUT_MS) { client.sent.receive() }

    /** Injects the synthetic local player and lets the server report it as playing. */
    private suspend fun playing(): Player {
        adapter.onInitialPlayersReceived(hasLocalPlayer = false)
        val synthetic = await { adapter.localPlayerData.value?.player }
        adapter.onServerPlayerUpdate(synthetic.copy(isPlaying = true))
        await { adapter.wantsSession.value.takeIf { it } }
        return synthetic
    }

    private suspend fun <T : Any> await(read: () -> T?): T = withTimeout(TIMEOUT_MS) {
        var value = read()
        while (value == null) {
            delay(POLL_MS)
            value = read()
        }
        value
    }

    private class FakePlayer : SendspinPlayer {
        override val state = MutableStateFlow<PlayerState>(PlayerState.Disabled)
        private val _events = MutableSharedFlow<PlayerEvent>(extraBufferCapacity = 16)
        override val events: Flow<PlayerEvent> = _events
        val held = MutableStateFlow(false)

        override fun hold(held: Boolean) {
            this.held.value = held
        }

        /** Waits for the adapter's collector first: the flow has no replay. */
        suspend fun emit(event: PlayerEvent) {
            _events.subscriptionCount.first { it > 0 }
            _events.emit(event)
        }
    }

    private class RecordingClient : StubServiceClient() {
        override val sessionState = MutableStateFlow<SessionState>(SessionState.Disconnected.Initial)
        override val isReadyForCommands = MutableStateFlow(true)
        val sent = Channel<Request>(Channel.UNLIMITED)
        val recoveries = MutableStateFlow(0)

        override suspend fun sendRequest(request: Request): Result<Answer> {
            sent.send(request)
            return Result.success(Answer(buildJsonObject {}))
        }

        override fun requestCommandRecovery() {
            recoveries.value++
        }
    }

    private companion object {
        val PAUSE = APICommands.playersCmd("pause")
        val PLAY = APICommands.playersCmd("play")
        val NEXT = APICommands.playersCmd("next")
        const val QUEUE_ID = "queue"
        const val EPISODE_POSITION_SEC = 1800.0
        const val TIMEOUT_MS = 5_000L
        const val POLL_MS = 10L
        const val SETTLE_MS = 300L
    }
}

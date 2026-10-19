package io.music_assistant.client.data

import com.russhwolf.settings.MapSettings
import io.music_assistant.client.api.Answer
import io.music_assistant.client.api.ConnectionInfo
import io.music_assistant.client.api.Request
import io.music_assistant.client.api.ServiceClient
import io.music_assistant.client.data.model.server.ServerInfo
import io.music_assistant.client.data.model.server.User
import io.music_assistant.client.data.model.server.events.Event
import io.music_assistant.client.settings.SettingsRepository
import io.music_assistant.client.utils.ConnectionData
import io.music_assistant.client.utils.SessionState
import io.music_assistant.client.webrtc.DataChannelInbound
import io.music_assistant.client.webrtc.DataChannelReceiveSource
import io.music_assistant.client.webrtc.DataChannelState
import io.music_assistant.client.webrtc.DataChannelWrapper
import io.music_assistant.client.webrtc.WebRTCDiagnostics
import io.music_assistant.client.webrtc.WebRTCHttpProxy
import io.music_assistant.client.webrtc.model.RemoteId
import io.music_assistant.sendspin.api.Endpoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A Sendspin reconnect over WebRTC opens a new channel on the live peer connection when the
 * server can route it, and restarts the whole WebRTC session (and the API session with it)
 * only when it cannot.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalPlayerEndpointsWebRtcTest {
    private val client = WebRtcClient()
    private val settings = SettingsRepository(MapSettings(), MapSettings())

    @Test
    fun theNegotiatedChannelServesTheFirstConnection() = runTest {
        val endpoint = webRtcEndpoint(schema = LABEL_ROUTING_SCHEMA)

        endpoint.openChannel()

        assertEquals(0, client.inBandOpens)
        assertEquals(0, client.forcedReconnects)
    }

    @Test
    fun aSpentChannelIsReplacedOnTheLivePeerConnection() = runTest {
        val endpoint = webRtcEndpoint(schema = LABEL_ROUTING_SCHEMA)
        endpoint.openChannel().close()

        client.nextInBand = { channel() }
        endpoint.openChannel()

        assertEquals(1, client.inBandOpens)
        assertEquals(0, client.forcedReconnects)
    }

    @Test
    fun anOldGatewayRenegotiates() = runTest {
        val endpoint = webRtcEndpoint(schema = IN_BAND_SENDSPIN_SCHEMA - 1)
        endpoint.openChannel().close()

        endpoint.openChannel()

        assertEquals(0, client.inBandOpens)
        assertEquals(1, client.forcedReconnects)
    }

    @Test
    fun anInBandChannelThatDoesNotOpenFallsBackToRenegotiation() = runTest {
        val endpoint = webRtcEndpoint(schema = LABEL_ROUTING_SCHEMA)
        endpoint.openChannel().close()

        client.nextInBand = { null }
        endpoint.openChannel()

        assertEquals(1, client.inBandOpens)
        assertEquals(1, client.forcedReconnects)
    }

    @Test
    fun inBandChannelsTheServerNeverAnsweredFallBackToRenegotiation() = runTest {
        val endpoint = webRtcEndpoint(schema = LABEL_ROUTING_SCHEMA)
        endpoint.openChannel().close()
        client.nextInBand = { channel() }
        repeat(SendspinChannelPlan.MAX_SILENT_IN_BAND) { endpoint.openChannel().close() }

        endpoint.openChannel()

        assertEquals(SendspinChannelPlan.MAX_SILENT_IN_BAND, client.inBandOpens)
        assertEquals(1, client.forcedReconnects)
    }

    private fun TestScope.webRtcEndpoint(schema: Int): Endpoint.WebRtc {
        val endpoints = LocalPlayerEndpoints(client, settings, backgroundScope)
        client.negotiated = channel()
        client.sessionState.value = SessionState.Connected.WebRTC(
            REMOTE_ID,
            ConnectionData(serverInfo = ServerInfo(serverId = "server-1", schemaVersion = schema), user = USER, token = "tok"),
        )
        runCurrent()
        return assertIs<Endpoint.WebRtc>(endpoints.endpoint.value)
    }

    private companion object {
        val REMOTE_ID = RemoteId("ABCDEFGHIJKLMNOPQRSTUVWXY2")
        val USER = User(username = "daveb")
        const val LABEL_ROUTING_SCHEMA = 63
    }
}

/** An open channel on which the server never sends anything. */
private fun channel() = DataChannelWrapper(
    dataChannel = null,
    connectionEvents = null,
    receiveSource = object : DataChannelReceiveSource {
        override suspend fun receive(): DataChannelInbound = awaitCancellation()
    },
    initialState = DataChannelState.Open,
    label = "sendspin",
    diagnostics = WebRTCDiagnostics(),
)

/** A WebRTC session whose renegotiation always brings a new negotiated channel. */
private class WebRtcClient : ServiceClient {
    override val sessionState = MutableStateFlow<SessionState>(SessionState.Disconnected.Initial)
    var negotiated: DataChannelWrapper? = null
    var nextInBand: () -> DataChannelWrapper? = { error("no in-band channel expected") }
    var inBandOpens = 0
    var forcedReconnects = 0

    override val webrtcSendspinChannel: DataChannelWrapper? get() = negotiated

    override suspend fun openWebRTCDataChannel(label: String): DataChannelWrapper? {
        assertEquals("sendspin", label)
        inBandOpens++
        return nextInBand()
    }

    override fun forceWebRTCReconnect() {
        forcedReconnects++
        val session = sessionState.value as? SessionState.Connected.WebRTC ?: return
        sessionState.value = SessionState.Reconnecting.WebRTC(0, session.remoteId, session.connectionData)
        negotiated = channel()
        sessionState.value = session
    }

    override val isReadyForCommands: StateFlow<Boolean> get() = error("not used")
    override val externalConsumerActive: StateFlow<Boolean> get() = error("not used")
    override val events: Flow<Event<out Any>> = emptyFlow()
    override val webRTCHttpProxy: WebRTCHttpProxy? = null
    override val foregroundEvents: Flow<Unit> = emptyFlow()

    override suspend fun sendRequest(request: Request): Result<Answer> = error("not used")
    override suspend fun login(username: String, password: String) = Unit
    override suspend fun authorize(token: String, isAutoLogin: Boolean) = Unit
    override fun logout() = Unit
    override fun resolveImageUrl(
        path: String,
        provider: String,
        isRemotelyAccessible: Boolean,
        proxyId: String?,
    ): String? = null
    override fun rebaseServerImageUrl(rawUrl: String): String? = null
    override fun onAppForeground() = Unit
    override fun onAppBackground() = Unit
    override fun disconnectByUser() = Unit
    override fun connect(connection: ConnectionInfo) = Unit
    override fun connectWebRTC(remoteId: RemoteId) = Unit
    override fun onExternalConsumerActive() = Unit
    override fun requestCommandRecovery() = Unit
    override fun onPlaybackActive() = Unit
    override fun onExternalConsumerInactive() = Unit
    override fun onPlaybackInactive() = Unit
    override fun forceDisconnect(reason: Exception) = Unit
    override fun noServer() = Unit
}

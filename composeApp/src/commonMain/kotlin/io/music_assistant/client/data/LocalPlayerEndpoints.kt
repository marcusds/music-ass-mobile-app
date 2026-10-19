package io.music_assistant.client.data

import co.touchlab.kermit.Logger
import io.music_assistant.client.api.ServiceClient
import io.music_assistant.client.data.model.server.ENCRYPTED_SENDSPIN_MIN_SCHEMA
import io.music_assistant.client.data.model.server.sendspinWireFor
import io.music_assistant.client.data.model.server.supportsEncryptedSendspin
import io.music_assistant.client.settings.SettingsRepository
import io.music_assistant.client.utils.AuthProcessState
import io.music_assistant.client.utils.DataConnectionState
import io.music_assistant.client.utils.SessionState
import io.music_assistant.client.utils.authenticatedToken
import io.music_assistant.client.webrtc.DataChannelInbound
import io.music_assistant.client.webrtc.DataChannelState
import io.music_assistant.client.webrtc.DataChannelWrapper
import io.music_assistant.sendspin.api.Endpoint
import io.music_assistant.sendspin.api.Frame
import io.music_assistant.sendspin.api.SendspinTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.Volatile

/**
 * Derives the Sendspin [Endpoint] from the MA session. The endpoint follows the
 * session's transport: a WebRTC session gets the data channel, a direct one the
 * proxied WebSocket (or the custom Sendspin server from settings).
 * A known schema below [ENCRYPTED_SENDSPIN_MIN_SCHEMA] yields [Endpoint.Unsupported];
 * an unknown schema is allowed through so the handshake decides. The schema and the
 * server version pick the wire dialect ([sendspinWireFor]); a wire change restarts the connection.
 *
 * Transient session states keep the last endpoint: the Sendspin connection
 * lives with the process and reconnects on its own, so a reconnecting MA
 * session must not tear it down. Only a logout, a terminal auth failure, or no
 * server at all clears it. The WebRTC endpoint instance is reused while the
 * session stays WebRTC, because a new instance restarts the connection.
 */
class LocalPlayerEndpoints(
    private val apiClient: ServiceClient,
    private val settings: SettingsRepository,
    scope: CoroutineScope,
) {
    private val log = Logger.withTag("LocalPlayerEndpoints")

    val endpoint: StateFlow<Endpoint?> = combine(
        apiClient.sessionState,
        settings.sendspinUseCustomConnection,
        settings.sendspinHost,
        settings.sendspinPort,
        settings.sendspinPath,
        settings.sendspinUseTls,
    ) { values -> values }
        .runningFold(null as Endpoint?) { previous, values -> next(previous, values[0] as SessionState) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** Pure reducer; a Connected session is Clear (terminal auth), Replace (proven), or Keep (auth pending). */
    internal fun next(previous: Endpoint?, state: SessionState): Endpoint? = when (state) {
        is SessionState.Connected -> when {
            state.authIsTerminal() -> null
            else -> connected(previous, state) ?: previous
        }

        is SessionState.Disconnected.ByUser,
        SessionState.Disconnected.Initial,
        SessionState.Disconnected.NoServerData,
        -> null

        else -> previous
    }

    /** A logout or a failed reauthorization keeps the session Connected; nothing follows on its own. */
    private fun SessionState.Connected.authIsTerminal(): Boolean {
        val connection = dataConnectionState as? DataConnectionState.AwaitingAuth ?: return false
        return connection.authProcessState is AuthProcessState.LoggedOut ||
            connection.authProcessState is AuthProcessState.Failed
    }

    /** The endpoint for a proven session, or null while authentication is still pending. */
    private fun connected(previous: Endpoint?, state: SessionState.Connected): Endpoint? {
        val schema = state.serverInfo?.schemaVersion
        // Missing schema is inconclusive, so let the wire handshake establish compatibility.
        if (schema != null && !supportsEncryptedSendspin(schema)) {
            return Endpoint.Unsupported(
                "MA server schema $schema does not support encrypted Sendspin; requires schema $ENCRYPTED_SENDSPIN_MIN_SCHEMA",
            )
        }
        val wire = sendspinWireFor(schema, state.serverInfo?.serverVersion)
        return when (state) {
            is SessionState.Connected.WebRTC ->
                (previous as? Endpoint.WebRtc)?.takeIf { it.wire == wire } ?: Endpoint.WebRtc(wire, ::openFreshChannel)

            is SessionState.Connected.Direct -> state.authenticatedToken()?.let { token ->
                Endpoint.WebSocket(webSocketUrl(state), token, wire)
            }
        }
    }

    private fun webSocketUrl(state: SessionState.Connected.Direct): String {
        if (settings.sendspinUseCustomConnection.value) {
            val scheme = if (settings.sendspinUseTls.value) "wss" else "ws"
            val host = settings.sendspinHost.value.takeIf { it.isNotEmpty() } ?: state.connectionInfo.host
            return "$scheme://$host:${settings.sendspinPort.value}${settings.sendspinPath.value}"
        }
        // Same reverse proxy as the control socket, so the same base path.
        return "${state.connectionInfo.wsUrl}/sendspin"
    }

    // --- WebRTC: one single-use channel per connection attempt ---

    // The Sendspin supervisor opens one connection at a time, so these need no lock.
    private val channelPlan = SendspinChannelPlan()
    private var usedChannel: DataChannelWrapper? = null
    private var lastInBand: DataChannelTransport? = null

    private suspend fun openFreshChannel(): SendspinTransport {
        lastInBand?.let { channelPlan.onInBandChannelEnded(it.receivedFrames) }
        lastInBand = null
        val negotiated = apiClient.webrtcSendspinChannel
            ?.takeIf { it !== usedChannel && it.state.value.isUsable() }
        var inBand: DataChannelWrapper? = null
        val fresh = when (channelPlan.next(negotiatedUnused = negotiated != null, inBandSupported = inBandSupported())) {
            SendspinChannelSource.NEGOTIATED -> negotiated
            SendspinChannelSource.IN_BAND -> openInBandChannel().also { inBand = it }
            SendspinChannelSource.RENEGOTIATE -> null
        } ?: renegotiate()
        val state = withTimeout(CHANNEL_TIMEOUT_MILLIS) {
            fresh.state.first { it == DataChannelState.Open || it == DataChannelState.Closed }
        }
        // The Sendspin supervisor catches this as a failed connect attempt and retries.
        check(state == DataChannelState.Open) { "Sendspin data channel closed before it opened" }
        usedChannel = fresh
        return DataChannelTransport(fresh).also { transport ->
            if (fresh === inBand) lastInBand = transport
        }
    }

    private fun DataChannelState.isUsable(): Boolean =
        this == DataChannelState.Connecting || this == DataChannelState.Open

    private fun inBandSupported(): Boolean =
        (apiClient.sessionState.value as? SessionState.Connected.WebRTC)
            ?.serverInfo?.schemaVersion
            ?.let { it >= IN_BAND_SENDSPIN_SCHEMA } == true

    /** A new `sendspin` channel on the live peer connection, or null when it does not open. */
    private suspend fun openInBandChannel(): DataChannelWrapper? {
        val channel = try {
            apiClient.openWebRTCDataChannel(SENDSPIN_CHANNEL_LABEL)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "Could not open a Sendspin data channel in-band" }
            null
        }
        channel?.let { log.i { "Sendspin data channel spent; opened a new one on the live peer connection" } }
            ?: log.i { "In-band Sendspin data channel did not open" }
        return channel
    }

    /** Restarts the peer connection and waits for its negotiated channel. Restarts the API session too. */
    private suspend fun renegotiate(): DataChannelWrapper {
        val spent = apiClient.webrtcSendspinChannel
        log.i { "Sendspin data channel spent; renegotiating WebRTC" }
        channelPlan.onRenegotiated()
        apiClient.forceWebRTCReconnect()
        // The channel is taken in the same step that sees it, so it cannot turn null after the wait.
        return withTimeout(CHANNEL_TIMEOUT_MILLIS) {
            apiClient.sessionState
                .mapNotNull { session ->
                    apiClient.webrtcSendspinChannel
                        ?.takeIf { session is SessionState.Connected.WebRTC && it !== spent }
                }
                .first()
        }
    }

    private companion object {
        const val CHANNEL_TIMEOUT_MILLIS = 30_000L
        const val SENDSPIN_CHANNEL_LABEL = "sendspin"
    }
}

/** [SendspinTransport] over one open [DataChannelWrapper]. */
private class DataChannelTransport(private val wrapper: DataChannelWrapper) : SendspinTransport {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** True once the server sent anything on this channel, which proves it accepted the channel. */
    @Volatile
    var receivedFrames = false
        private set

    override val inbound: ReceiveChannel<Frame> = scope.produce(capacity = INBOUND_CAPACITY) {
        wrapper.inbound.collect { message ->
            receivedFrames = true
            send(
                when (message) {
                    is DataChannelInbound.Text -> Frame.Text(message.text)
                    is DataChannelInbound.Binary -> Frame.Binary(message.bytes)
                },
            )
        }
    }

    override suspend fun send(frame: Frame) {
        check(wrapper.state.value == DataChannelState.Open) { "data channel not open (${wrapper.state.value})" }
        when (frame) {
            is Frame.Text -> wrapper.send(frame.text)
            is Frame.Binary -> wrapper.sendBinary(frame.bytes)
        }
    }

    override suspend fun close() {
        wrapper.close()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    private companion object {
        const val INBOUND_CAPACITY = 64
    }
}

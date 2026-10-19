// Log-payload truncation lengths and connection delays are inline-documented at use site.
@file:Suppress("MagicNumber")

package io.music_assistant.client.api

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.music_assistant.client.utils.myJson
import io.music_assistant.client.webrtc.DataChannelInbound
import io.music_assistant.client.webrtc.DataChannelWrapper
import io.music_assistant.client.webrtc.SignalingClient
import io.music_assistant.client.webrtc.WebRTCConnectionManager
import io.music_assistant.client.webrtc.WebRTCHttpProxy
import io.music_assistant.client.webrtc.model.RemoteId
import io.music_assistant.client.webrtc.model.WebRTCConnectionState
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.concurrent.Volatile
import kotlin.io.encoding.Base64

private const val HTTP_PROXY_TYPE_SCAN_WINDOW = 256
private const val HTTP_PROXY_TYPE_TOKEN = "\"type\":\"http-proxy-response\""
private const val CHUNK_TYPE_TOKEN = "\"__chunk__\""
private const val MAX_CHUNK_COUNT = 256

// Server API schema that introduced label-based data-channel routing and the dedicated
// `http_proxy` channel (music-assistant/server#5635, MA 2.10).
private const val HTTP_PROXY_CHANNEL_SCHEMA = 49
internal const val MAX_PENDING_CHUNK_GROUPS = 16
private const val MAX_REASSEMBLED_BYTES = 16 * 1024 * 1024

// A handoff can report the loss of the old network a moment before the new one arrives.
// A longer outage still ends the connection before the ICE check would (detection + 4 s grace).
private const val NETWORK_LOSS_GRACE_MS = 6_000L

// Bounds the teardown of a replaced manager, which the next attempt waits for.
private const val MANAGER_DISPOSE_TIMEOUT_MS = 5_000L

/** Reassembles server-side `__chunk__` envelopes while passing legacy whole messages through. */
internal class WebRTCChunkReassembler {
    private class PendingGroup(
        val count: Int,
        val parts: Array<ByteArray?>,
        var received: Int = 0,
        var totalBytes: Int = 0,
    )

    // Insertion order lets us evict the oldest incomplete group if a buggy peer continuously
    // starts groups without finishing them. The instance is confined to one listener coroutine.
    private val groups = linkedMapOf<Int, PendingGroup>()

    fun accept(message: String): String? {
        // The server always emits `type` first. Bound the fast scan so legacy multi-MB responses
        // do not incur another full payload pass before the existing bounded proxy-type scan.
        val scanEnd = minOf(HTTP_PROXY_TYPE_SCAN_WINDOW, message.length)
        if (message.indexOf(CHUNK_TYPE_TOKEN) !in 0 until scanEnd) return message

        val frame = runCatching { myJson.decodeFromString<JsonObject>(message) }.getOrNull()
            ?: return message
        if (frame.string("type") != "__chunk__") return message

        // Once identified as a chunk envelope, malformed frames are consumed rather than leaked
        // into the normal API dispatcher. A later valid frame/group can still be processed.
        val id = frame.int("id") ?: return null
        val seq = frame.int("seq") ?: return null
        val count = frame.int("count") ?: return null
        val encoded = frame.string("b64") ?: return null
        if (count !in 1..MAX_CHUNK_COUNT || seq !in 0 until count) return null

        val existing = groups[id]
        if (existing != null && existing.count != count) return null
        val bytes = runCatching { Base64.decode(encoded) }.getOrNull() ?: return null
        val pending = existing ?: registerGroup(id, count)
        val previous = pending.parts[seq]
        val newTotal = pending.totalBytes - (previous?.size ?: 0) + bytes.size
        if (newTotal > MAX_REASSEMBLED_BYTES) {
            groups.remove(id)
            return null
        }
        if (previous == null) pending.received++
        pending.parts[seq] = bytes
        pending.totalBytes = newTotal
        if (pending.received < pending.count) return null

        val assembled = ByteArray(pending.totalBytes)
        var offset = 0
        // received == count guarantees every valid sequence slot has been populated.
        for (part in pending.parts) {
            val bytes = checkNotNull(part)
            bytes.copyInto(assembled, destinationOffset = offset)
            offset += bytes.size
        }
        groups.remove(id)
        return runCatching { assembled.decodeToString(throwOnInvalidSequence = true) }.getOrNull()
    }

    private fun registerGroup(id: Int, count: Int): PendingGroup {
        if (groups.size >= MAX_PENDING_CHUNK_GROUPS) {
            groups.remove(groups.keys.first())
        }
        return PendingGroup(count, arrayOfNulls(count)).also { groups[id] = it }
    }
}

private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull

private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

private fun isHttpProxyResponse(jsonString: String): Boolean {
    val end = minOf(HTTP_PROXY_TYPE_SCAN_WINDOW, jsonString.length)
    // Compact form (no whitespace) is what `json.dumps` / `JSON.stringify` produce by default.
    if (jsonString.regionMatches(0, "{", 0, 1) &&
        jsonString.indexOf(HTTP_PROXY_TYPE_TOKEN, startIndex = 0, ignoreCase = false) in 0 until end
    ) {
        return true
    }
    // Whitespace-tolerant fallback (rare — only if server pretty-prints).
    return HTTP_PROXY_TYPE_REGEX.containsMatchIn(jsonString.substring(0, end))
}

private val HTTP_PROXY_TYPE_REGEX = Regex("\"type\"\\s*:\\s*\"http-proxy-response\"")

class WebRTCTransport(
    private val httpClient: HttpClient,
    private val remoteId: RemoteId,
    private val parentScope: CoroutineScope,
    private val networkAvailable: StateFlow<Boolean>? = null,
    private val maxReconnectAttempts: Int = DEFAULT_MAX_RECONNECT_ATTEMPTS,
) : Transport {
    private val logger = Logger.withTag("WebRTCTransport")

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        logger.e(throwable) { "Uncaught exception in WebRTCTransport scope" }
        when (_state.value) {
            TransportState.Connected -> forceReconnect()
            TransportState.Connecting, is TransportState.Reconnecting ->
                _state.value = TransportState.Failed(
                    Exception("Recovery machinery died: ${throwable.message}", throwable),
                )
            TransportState.Disconnected, is TransportState.Failed -> Unit
        }
    }

    private val scope: CoroutineScope = CoroutineScope(
        parentScope.coroutineContext +
            SupervisorJob(parentScope.coroutineContext[Job]) +
            exceptionHandler,
    )

    private val _state = MutableStateFlow<TransportState>(TransportState.Disconnected)
    override val state = _state.asStateFlow()

    // Larger buffer: under image-burst load on the shared `ma-api` channel, RpcEngine can lag
    // briefly behind the listener. A bigger buffer avoids backpressuring the listener (which
    // would otherwise stall hex-decoded image responses behind control-plane frame parsing).
    private val _messages = MutableSharedFlow<JsonObject>(extraBufferCapacity = 64)
    override val messages = _messages.asSharedFlow()

    // The ICE monitor, the network watch, forced reconnects and disconnect() all run on
    // different Dispatchers.IO threads. Every write of the fields below happens under this
    // lock, and an attempt adopts a manager or publishes a state only while it is still live.
    private val lock = SynchronizedObject()

    @Volatile
    private var manager: WebRTCConnectionManager? = null

    // The one job that connects or reconnects. A new start waits until the old job has
    // finished, so two attempts never build managers at the same time.
    private val connection = SerialJobSlot(scope)
    private var messageListenerJob: Job? = null
    private var stateMonitorJob: Job? = null
    private var networkWatchJob: Job? = null
    private var httpProxyChannelJob: Job? = null

    // Feature detection runs once per connection; cleared with the manager it belongs to.
    private var httpProxyChannelRequested = false

    val sendspinDataChannel: DataChannelWrapper?
        get() = manager?.sendspinDataChannel

    suspend fun openDataChannel(label: String): DataChannelWrapper? = manager?.openDataChannel(label)

    val httpProxy: WebRTCHttpProxy = WebRTCHttpProxy(sender = { json -> send(json) })

    override fun connect() {
        startNetworkWatchIfNeeded()
        connection.replace {
            val attempt = currentCoroutineContext()[Job]
            publish(attempt, TransportState.Connecting)
            connectInternal(isReconnect = false)
        }
    }

    /**
     * Observes the OS-level default network. When it stays lost for [NETWORK_LOSS_GRACE_MS]
     * under a live connection, tear down and kick reconnection instead of waiting for the
     * ICE liveness check. A shorter loss is a handoff (Wi-Fi to cellular, a VPN reconnect):
     * the connection is kept, and the manager's ICE monitor fails it if the path died.
     *
     * Does NOT catch mid-call link-quality degradation (weak signal, packet loss) — the
     * OS still considers the interface up in that case; only ICE/keepalive timeouts fire.
     */
    private fun startNetworkWatchIfNeeded() {
        val net = networkAvailable ?: return
        synchronized(lock) {
            if (networkWatchJob?.isActive == true) return
            networkWatchJob = scope.watchNetworkLoss(
                networkAvailable = net,
                graceMs = NETWORK_LOSS_GRACE_MS,
                liveConnection = { manager?.takeIf { _state.value is TransportState.Connected } },
                onLossSeen = {
                    logger.w { "Default network lost — waiting ${NETWORK_LOSS_GRACE_MS}ms for a replacement" }
                    manager?.diagnostics?.event("network lost; grace ${NETWORK_LOSS_GRACE_MS}ms started")
                },
                onLossConfirmed = {
                    logger.w { "Default network still lost — aborting connection (skipping libwebrtc ICE timeout)" }
                    onNetworkLost()
                },
            )
        }
    }

    private fun onNetworkLost() {
        manager?.diagnostics?.event("reconnect trigger=network_lost")
        // Pre-empt the slow path: tear down the manager and start the reconnection loop.
        // The loop gates on networkAvailable, so it waits until a network is back before
        // attempting. Replacing the connection job also stops an in-flight forced reconnect.
        connection.replace {
            cleanupManager()
            startReconnection()
        }
    }

    private suspend fun connectInternal(isReconnect: Boolean) {
        val attempt = currentCoroutineContext()[Job]
        try {
            cleanupManager()
            val mgr = createManager()
            if (!adopt(attempt, mgr)) {
                // disconnect() cancelled this attempt and already took the old manager.
                disposeManager(mgr)
                return
            }
            mgr.diagnostics.event("transport attempt started reconnect=$isReconnect")
            mgr.connect(remoteId)

            // Wait for terminal WebRTC connection state (Connected or Error)
            val result = mgr.connectionState.first { connState ->
                connState is WebRTCConnectionState.Connected || connState is WebRTCConnectionState.Error
            }

            when (result) {
                is WebRTCConnectionState.Connected -> {
                    if (goLive(attempt, mgr)) mgr.diagnostics.event("transport attempt succeeded reconnect=$isReconnect")
                }

                is WebRTCConnectionState.Error -> {
                    mgr.diagnostics.event(
                        "transport attempt failed reconnect=$isReconnect error=${result.error::class.simpleName}",
                    )
                    if (!isReconnect) {
                        publish(attempt, TransportState.Failed(Exception("WebRTC connection failed: ${result.error}")))
                    }
                }

                else -> {} // unreachable
            }
        } catch (e: Exception) {
            manager?.diagnostics?.failure("transport attempt ended reconnect=$isReconnect", e)
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (!isReconnect) {
                publish(attempt, TransportState.Failed(e))
            }
        }
    }

    /** Makes [mgr] the current manager, unless [attempt] was cancelled. */
    private fun adopt(attempt: Job?, mgr: WebRTCConnectionManager): Boolean = synchronized(lock) {
        (attempt?.isActive != false).also { if (it) manager = mgr }
    }

    /**
     * Publishes Connected and starts the listener and the monitor for [mgr], unless [attempt]
     * was cancelled or [mgr] was replaced while it connected.
     */
    private fun goLive(attempt: Job?, mgr: WebRTCConnectionManager): Boolean = synchronized(lock) {
        val live = attempt?.isActive != false && manager === mgr
        if (live) {
            _state.value = TransportState.Connected
            startMessageListener(mgr)
            startStateMonitor(mgr)
        }
        live
    }

    /** Sets [state] unless [attempt] was cancelled, so a replaced attempt cannot overwrite a newer state. */
    private fun publish(attempt: Job?, state: TransportState) {
        synchronized(lock) {
            if (attempt?.isActive != false) _state.value = state
        }
    }

    /** Caller holds [lock]. */
    private fun startMessageListener(mgr: WebRTCConnectionManager) {
        messageListenerJob?.cancel()
        messageListenerJob = scope.launch {
            // Listener-local ownership avoids mutable reassembly state racing lifecycle teardown.
            val chunkReassembler = WebRTCChunkReassembler()
            try {
                mgr.incomingMessages.collect { wireMessage ->
                    try {
                        // New servers chunk messages that exceed libdatachannel's 256 KiB limit.
                        // Legacy servers still send whole messages, which pass through unchanged.
                        val jsonString = chunkReassembler.accept(wireMessage) ?: return@collect

                        // CHEAP peek: avoid full JSON parse for multi-MB http-proxy-response frames.
                        // The full parse would block the listener (single coroutine), queueing every
                        // subsequent control-plane message behind each image body for hundreds of ms.
                        // Bounded to first 256 chars — `type` is always early in the object.
                        if (isHttpProxyResponse(jsonString)) {
                            httpProxy.dispatchRawResponse(jsonString)
                        } else {
                            val jsonObject = myJson.decodeFromString<JsonObject>(jsonString)
                            maybeOpenHttpProxyChannel(mgr, jsonObject)
                            _messages.emit(jsonObject)
                        }
                    } catch (e: Exception) {
                        // For chunked traffic wireMessage is only the final envelope, not the
                        // reassembled payload, so identify the stage without mislabeling the frame.
                        logger.e(e) { "Failed to dispatch incoming WebRTC frame: ${wireMessage.take(200)}" }
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                logger.d { "WebRTC message listener ended: ${e.message}" }
            }
        }
    }

    /**
     * Opens the dedicated image channel once per connection, as soon as the server proves it
     * can route the label. `server_info` is the first frame on `ma-api` and carries the
     * schema version; a server below [HTTP_PROXY_CHANNEL_SCHEMA] mistakes an unknown label
     * for the API channel and drops the whole session, so this must never be speculative.
     */
    private fun maybeOpenHttpProxyChannel(mgr: WebRTCConnectionManager, message: JsonObject) {
        if (httpProxyChannelRequested) return
        val schema = (message["schema_version"] as? JsonPrimitive)?.intOrNull ?: return
        synchronized(lock) {
            // A listener that outlived its manager must not open a channel on it.
            if (httpProxyChannelRequested || manager !== mgr) return
            httpProxyChannelRequested = true
            if (schema < HTTP_PROXY_CHANNEL_SCHEMA) {
                logger.i { "Server schema $schema predates the image channel — proxying over ma-api" }
                return
            }
            httpProxyChannelJob = scope.launch { serveHttpProxyChannel(mgr) }
        }
    }

    private suspend fun serveHttpProxyChannel(mgr: WebRTCConnectionManager) {
        val channel = try {
            mgr.openHttpProxyChannel()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // Proxying over the API channel remains a working fallback.
            logger.w(e) { "http_proxy channel unavailable — images stay on ma-api" }
            null
        } ?: return

        val attachment = httpProxy.attachChannel { json ->
            channel.send(myJson.encodeToString(JsonObject.serializer(), json))
        }
        try {
            // Listener-local, like the ma-api reassembler: a schema-49 server that predates
            // the binary framing still answers here in hex, chunked when oversized.
            val chunkReassembler = WebRTCChunkReassembler()
            channel.inbound.collect { inbound ->
                when (inbound) {
                    is DataChannelInbound.Text ->
                        chunkReassembler.accept(inbound.text)
                            ?.let { httpProxy.dispatchProxyChannelText(it) }

                    is DataChannelInbound.Binary ->
                        httpProxy.dispatchProxyChannelBinary(inbound.bytes)
                }
            }
        } finally {
            // The inbound stream ends when the channel dies. Run the detach uncancellable so
            // callers are failed even when this job is torn down mid-collect.
            withContext(NonCancellable) {
                httpProxy.detachChannel(attachment, IllegalStateException("http_proxy channel closed"))
            }
        }
    }

    /** Caller holds [lock]. */
    private fun startStateMonitor(mgr: WebRTCConnectionManager) {
        stateMonitorJob?.cancel()
        stateMonitorJob = scope.launch {
            // Wait for error state — first() completes when predicate matches
            val errorState = mgr.connectionState.first { it is WebRTCConnectionState.Error }
            mgr.diagnostics.event("reconnect trigger=manager_error state=${errorState::class.simpleName}")
            // The reconnection runs in the connection job, not here, so cleanupManager() can
            // cancel this monitor without killing it. A manager that was already replaced
            // (a forced reconnect or a network loss got there first) starts nothing.
            connection.replace(onlyIf = { manager === mgr }) { startReconnection() }
        }
    }

    private suspend fun startReconnection() {
        val attempt = currentCoroutineContext()[Job]
        val reconnected = runReconnectionLoop(
            maxAttempts = maxReconnectAttempts,
            networkAvailable = networkAvailable,
            onAttemptStarting = { publish(attempt, TransportState.Reconnecting(it)) },
            tryConnect = { n ->
                logger.i { "WebRTC reconnect attempt $n/$maxReconnectAttempts" }
                connectInternal(isReconnect = true)
                _state.value == TransportState.Connected
            },
        )
        manager?.diagnostics?.event("reconnect loop completed success=$reconnected")
        if (!reconnected) {
            publish(attempt, TransportState.Failed(Exception("Max WebRTC reconnect attempts reached")))
        }
    }

    fun forceReconnect() {
        manager?.diagnostics?.event("reconnect trigger=forced")
        connection.replace {
            cleanupManager()
            delay(1500) // wait for signaling server to process disconnect
            logger.i { "Starting fresh WebRTC connection after forced disconnect" }
            startReconnection()
        }
    }

    override suspend fun send(message: JsonObject) {
        val mgr = manager ?: error("Not connected")
        val jsonString = myJson.encodeToString(JsonObject.serializer(), message)
        mgr.send(jsonString)
    }

    override fun disconnect() {
        val mgr = synchronized(lock) {
            val current = manager
            // Cleared before the cancel: a state monitor that fires now finds no manager of
            // its own, so it cannot start a reconnect after the cancel.
            manager = null
            connection.cancel()
            messageListenerJob?.cancel()
            messageListenerJob = null
            stateMonitorJob?.cancel()
            stateMonitorJob = null
            networkWatchJob?.cancel()
            networkWatchJob = null
            httpProxyChannelJob?.cancel()
            httpProxyChannelJob = null
            httpProxyChannelRequested = false
            _state.value = TransportState.Disconnected
            current
        }
        // close() usually follows at once and cancels this transport's scope, which would
        // cancel the teardown before or during its run. It runs in the parent scope instead,
        // uncancellable, so the peer connection and the signaling socket always close.
        parentScope.launch {
            withContext(NonCancellable) {
                mgr?.let { disposeManager(it) }
                httpProxy.cancelAll(IllegalStateException("WebRTC transport disconnected"))
            }
        }
    }

    override fun close() {
        scope.cancel()
    }

    /** Cleans up the current manager and its listener jobs. Does NOT cancel the connection job. */
    private suspend fun cleanupManager() {
        val old = synchronized(lock) {
            messageListenerJob?.cancel()
            messageListenerJob = null
            stateMonitorJob?.cancel()
            stateMonitorJob = null
            httpProxyChannelJob?.cancel()
            httpProxyChannelJob = null
            httpProxyChannelRequested = false
            manager.also { manager = null }
        }
        old?.let { disposeManager(it) }
        httpProxy.cancelAll(IllegalStateException("WebRTC transport cleanup"))
    }

    /**
     * Disconnects a manager that no longer is current. It runs even in a cancelled caller,
     * because the field no longer holds the manager and nothing else would close it. The
     * timeout keeps a hung teardown from blocking the next attempt, which waits for this one.
     */
    private suspend fun disposeManager(mgr: WebRTCConnectionManager) {
        withContext(NonCancellable) {
            withTimeoutOrNull(MANAGER_DISPOSE_TIMEOUT_MS) { mgr.disconnect() }
                ?: logger.w { "WebRTC manager [${mgr.hashCode()}] did not disconnect in ${MANAGER_DISPOSE_TIMEOUT_MS}ms" }
        }
    }

    private fun createManager(): WebRTCConnectionManager {
        val signalingClient = SignalingClient(httpClient, scope)
        val mgr = WebRTCConnectionManager(signalingClient, scope)
        logger.d { "Created new WebRTC manager [${mgr.hashCode()}]" }
        return mgr
    }
}

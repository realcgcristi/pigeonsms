package app.pigeonsms.network

import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class GatewayStatus { Connecting, Connected, Disconnected }

/** Consecutive dead-on-arrival connects (never reached Connected) before we assume the token
 *  is stale and invoke [Gateway]'s onAuthFailure callback instead of retrying blindly. */
private const val AUTH_FAILURE_THRESHOLD = 3

/**
 * Single device WebSocket to /gateway. Auto-reconnects with backoff; every
 * server event lands on [events]. Presence pings keep the socket warm.
 */
class Gateway(
    private val api: PigeonApi,
    private val scope: CoroutineScope,
    private val tokenProvider: suspend () -> String?,
    private val onAuthFailure: suspend () -> Unit = {},
) {
    private val _events = MutableSharedFlow<GatewayEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<GatewayEvent> = _events

    private val _status = MutableStateFlow(GatewayStatus.Disconnected)
    val status: StateFlow<GatewayStatus> = _status

    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            var backoff = 1000L
            // Consecutive connects that died before ever reaching Connected — a stale/expired
            // token makes the server close the upgrade immediately, so this reconnects with the
            // same dead token forever unless something forces a refresh.
            var consecutiveImmediateFailures = 0
            while (isActive) {
                // no token yet (cold start / session switch) — wait and retry; bailing
                // out here left the gateway dead until the next app restart
                val token = tokenProvider()
                if (token == null) { delay(1000); continue }
                if (consecutiveImmediateFailures >= AUTH_FAILURE_THRESHOLD) {
                    runCatching { onAuthFailure() }
                    consecutiveImmediateFailures = 0
                    // re-fetch: onAuthFailure is expected to refresh whatever tokenProvider reads
                    delay(backoff)
                    continue
                }
                _status.value = GatewayStatus.Connecting
                var reachedConnected = false
                try {
                    api.client.webSocket("$PIGEON_WS?token=$token") {
                        reachedConnected = true
                        _status.value = GatewayStatus.Connected
                        backoff = 1000L
                        consecutiveImmediateFailures = 0
                        val pinger = launch {
                            while (isActive) { delay(25_000); runCatching { send("ping") } }
                        }
                        try {
                            for (frame in incoming) {
                                if (frame is Frame.Text) {
                                    val text = frame.readText()
                                    if (text == "pong") continue
                                    runCatching { api.json.decodeFromString(GatewayEvent.serializer(), text) }
                                        .onFailure { android.util.Log.w("Gateway", "dropped undecodable event: ${it.message?.take(120)}") }
                                        .getOrNull()?.let { _events.emit(it) }
                                }
                            }
                        } finally {
                            pinger.cancel()
                        }
                    }
                } catch (_: Exception) {
                    // fallthrough to reconnect
                }
                if (!reachedConnected) consecutiveImmediateFailures++
                _status.value = GatewayStatus.Disconnected
                if (!isActive) break
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _status.value = GatewayStatus.Disconnected
    }
}

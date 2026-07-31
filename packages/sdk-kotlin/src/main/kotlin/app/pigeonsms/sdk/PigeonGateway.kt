package app.pigeonsms.sdk

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.http.URLProtocol
import io.ktor.http.path
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.min

class PigeonGateway(
    private val url: String,
    private val tokenProvider: suspend () -> String?,
    private val cursors: suspend () -> Map<Snowflake, Long> = { emptyMap() },
    private val http: HttpClient = PigeonClient.defaultHttpClient(),
) {
    enum class Status { Idle, Connecting, Connected, Disconnected }

    private val handlers = mutableMapOf<String, CopyOnWriteArraySet<suspend (GatewayEvent) -> Unit>>()
    private val statusHandlers = CopyOnWriteArraySet<(Status) -> Unit>()
    private var job: Job? = null
    var status = Status.Idle
        private set

    fun on(name: String, handler: suspend (GatewayEvent) -> Unit): AutoCloseable {
        handlers.getOrPut(name) { CopyOnWriteArraySet() }.add(handler)
        return AutoCloseable { handlers[name]?.remove(handler) }
    }

    fun onStatus(handler: (Status) -> Unit): AutoCloseable {
        statusHandlers.add(handler)
        handler(status)
        return AutoCloseable { statusHandlers.remove(handler) }
    }

    fun start(scope: CoroutineScope): Job {
        job?.cancel()
        return scope.launch { reconnectLoop() }.also { job = it }
    }

    fun stop() {
        job?.cancel()
        job = null
        updateStatus(Status.Idle)
    }

    private suspend fun reconnectLoop() {
        var backoff = 500L
        while (kotlin.coroutines.coroutineContext.isActive) {
            val token = tokenProvider()
            if (token == null) {
                delay(1000)
                continue
            }
            updateStatus(Status.Connecting)
            try {
                connect(token)
                backoff = 500
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                updateStatus(Status.Disconnected)
            }
            delay(backoff)
            backoff = min(backoff * 2, 30_000)
        }
    }

    private suspend fun connect(token: String) {
        val target = java.net.URI(url)
        val resume = cursors().takeIf { it.isNotEmpty() }?.let {
            val json = buildJsonObject { it.forEach { (channel, seq) -> put(channel, seq) } }.toString()
            Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
        }
        http.webSocket({
            url {
                protocol = if (target.scheme == "wss") URLProtocol.WSS else URLProtocol.WS
                host = target.host
                port = if (target.port > 0) target.port else protocol.defaultPort
                path(target.path)
                if (token != "cookie") parameters.append("token", token)
                if (resume != null) parameters.append("resume", resume)
            }
        }) {
            updateStatus(Status.Connected)
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val text = frame.readText()
                if (text == "pong") continue
                val event = runCatching { PigeonClient.json.decodeFromString<GatewayEvent>(text) }.getOrNull() ?: continue
                for (handler in handlers[event.t].orEmpty()) handler(event)
                for (handler in handlers["*"].orEmpty()) handler(event)
            }
        }
    }

    private fun updateStatus(next: Status) {
        if (next == status) return
        status = next
        statusHandlers.forEach { it(next) }
    }
}

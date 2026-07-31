package app.pigeonsms.sdk

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PigeonClientTest {
    @Test
    fun discoveryUsesProtocolHeader() = runTest {
        val engine = MockEngine { request ->
            assertEquals("1.0", request.headers["Pigeon-Protocol-Version"])
            respond(
                """{"protocol":{"name":"open-pigeon","versions":["1.0"],"preferred":"1.0"},"server":{"name":"test","version":"1"},"endpoints":{"api":"https://x","gateway":"wss://x/gateway","media":"https://x/media"},"capabilities":["core"],"limits":{"message_length":8000,"upload_bytes":10}}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val http = HttpClient(engine) { install(ContentNegotiation) { json(PigeonClient.json) } }
        val discovery = PigeonClient("https://x", http = http).discover()
        assertEquals("open-pigeon", discovery.protocol.name)
    }
}

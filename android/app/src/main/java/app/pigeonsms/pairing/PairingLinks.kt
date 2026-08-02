package app.pigeonsms.pairing

import app.pigeonsms.network.PIGEON_BASE
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64

data class ParsedPairingInvite(
    val id: String,
    val secret: String,
    val api: String,
)

object PairingLinks {
    private val idPattern = Regex("^\\d{8,32}$")
    private val secretPattern = Regex("^[A-Za-z0-9_-]{43}$")

    fun parse(value: String): ParsedPairingInvite? {
        return runCatching {
            val uri = URI(value.trim())
            val custom = uri.scheme.equals("pigeonsms", true) && uri.host.equals("pair", true)
            val web = uri.scheme?.lowercase() in setOf("https", "http") && uri.path == "/pair"
            if (!custom && !web) return@runCatching null
            val query = query(uri)
            val id = query["pairing_id"].orEmpty()
            val secret = query["secret"].orEmpty()
            val api = normalizeOrigin(query["api"].orEmpty()) ?: return@runCatching null
            val expected = normalizeOrigin(PIGEON_BASE) ?: return@runCatching null
            if (!idPattern.matches(id) || !secretPattern.matches(secret) || api != expected) return@runCatching null
            ParsedPairingInvite(id, secret, api)
        }.getOrNull()
    }

    fun claimSecret(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun normalizeOrigin(value: String): String? {
        val uri = URI(value)
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (scheme != "https" && host != "localhost" && host != "127.0.0.1") return null
        val port = uri.port
        val portPart = if (port == -1 || scheme == "https" && port == 443 || scheme == "http" && port == 80) "" else ":$port"
        return "$scheme://$host$portPart"
    }

    private fun query(uri: URI): Map<String, String> {
        return uri.rawQuery.orEmpty().split('&').mapNotNull { part ->
            if (part.isEmpty()) return@mapNotNull null
            val pieces = part.split('=', limit = 2)
            val key = URLDecoder.decode(pieces[0], StandardCharsets.UTF_8.name())
            val value = URLDecoder.decode(pieces.getOrElse(1) { "" }, StandardCharsets.UTF_8.name())
            key to value
        }.toMap()
    }
}

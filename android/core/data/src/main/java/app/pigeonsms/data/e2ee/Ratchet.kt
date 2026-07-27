package app.pigeonsms.data.e2ee

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * A compact, symmetric-key Double Ratchet over the per-DM key (EXPERIMENTAL, flag-OFF).
 *
 * SCOPE / SIMPLIFICATION: this is the *symmetric* ratchet only — root + sending +
 * receiving chains keyed off a shared DM key delivered via sealed-box [KeyEnvelopeDto].
 * It gives forward secrecy across messages within an established key. It intentionally
 * does NOT (yet) do the DH ratchet step (fresh ephemeral X25519 per round-trip) that
 * gives break-in recovery — both sides derive their initial root from the SAME shared
 * key, so we skip the header-key DH exchange for v1.
 *
 * TODO(e2ee): add the DH ratchet (ephemeral keys in the message header, root-chain KDF
 * on each received new ratchet key) for post-compromise security. Requires on-device
 * testing of out-of-order delivery + the skipped-message-key cache below.
 * TODO(e2ee): bound [skipped] and persist/evict it; right now MAX_SKIP caps a single
 * gap but the map is unbounded across gaps.
 *
 * Wire format of one ciphertext (before base64): header(counter as 8-byte BE) then the
 * AEAD box from [Sodium.aeadSeal] with the header as associated data.
 */
internal class Ratchet(private var state: RatchetState) {

    val snapshot: RatchetState get() = state

    /** Encrypt with the next sending message key, then advance the sending chain. */
    fun encrypt(plaintext: ByteArray): ByteArray {
        val (chain, mk) = kdfChain(state.sendChainKey)
        val n = state.sendCount
        state = state.copy(sendChainKey = chain, sendCount = n + 1)
        val header = counterHeader(n)
        val box = Sodium.aeadSeal(mk, plaintext, header)
        return header + box
    }

    /** Decrypt; tolerates skipped/out-of-order counters up to [MAX_SKIP] within a gap. */
    fun decrypt(ciphertext: ByteArray): ByteArray {
        require(ciphertext.size > HEADER_LEN) { "ciphertext too short" }
        val header = ciphertext.copyOfRange(0, HEADER_LEN)
        val box = ciphertext.copyOfRange(HEADER_LEN, ciphertext.size)
        val n = readCounter(header)

        // 1) A previously-skipped message key?
        state.skipped[n]?.let { mk ->
            val pt = Sodium.aeadOpen(mk, box, header)
            val remaining = state.skipped.toMutableMap().apply { remove(n) }
            state = state.copy(skipped = remaining)
            return pt
        }

        require(n >= state.recvCount) { "stale/replayed counter $n < ${state.recvCount}" }

        // 2) Advance the receiving chain to n, caching intermediate message keys.
        var chain = state.recvChainKey
        val skipped = state.skipped.toMutableMap()
        var i = state.recvCount
        require(n - i <= MAX_SKIP) { "skip gap too large ($i..$n)" }
        while (i < n) {
            val (next, mk) = kdfChain(chain)
            skipped[i] = mk
            chain = next
            i++
        }
        val (finalChain, msgKey) = kdfChain(chain)
        val pt = Sodium.aeadOpen(msgKey, box, header)
        state = state.copy(recvChainKey = finalChain, recvCount = n + 1, skipped = skipped)
        return pt
    }

    companion object {
        const val MAX_SKIP = 1000
        const val HEADER_LEN = 8

        fun counterHeader(n: Long): ByteArray {
            val b = ByteArray(HEADER_LEN)
            for (i in 0 until HEADER_LEN) b[HEADER_LEN - 1 - i] = (n ushr (8 * i)).toByte()
            return b
        }

        fun readCounter(h: ByteArray): Long {
            var v = 0L
            for (i in 0 until HEADER_LEN) v = (v shl 8) or (h[i].toLong() and 0xFF)
            return v
        }

        /**
         * Symmetric-key ratchet step (Signal spec 5.2): from a chain key derive the
         * next chain key (HMAC over 0x02) and this message key (HMAC over 0x01).
         */
        fun kdfChain(chainKey: ByteArray): Pair<ByteArray, ByteArray> {
            val next = hmac(chainKey, byteArrayOf(0x02))
            val messageKey = hmac(chainKey, byteArrayOf(0x01)).copyOf(Sodium.AEAD_KEYBYTES)
            return next to messageKey
        }

        fun hmac(key: ByteArray, data: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(data)
        }

        /**
         * Initialise both peers deterministically from the shared DM key so no DH
         * exchange is needed for v1. [initiator] picks which HKDF label seeds the
         * sending vs receiving chain, so the two sides mirror each other.
         */
        fun fromSharedKey(channelId: String, sharedKey: ByteArray, initiator: Boolean): Ratchet {
            val root = hkdf(sharedKey, "pigeon-e2ee-root".toByteArray(), 32)
            val a = hkdf(root, "chain-A".toByteArray(), 32)
            val b = hkdf(root, "chain-B".toByteArray(), 32)
            val send = if (initiator) a else b
            val recv = if (initiator) b else a
            return Ratchet(
                RatchetState(
                    channelId = channelId,
                    sendChainKey = send,
                    recvChainKey = recv,
                    sendCount = 0,
                    recvCount = 0,
                    skipped = emptyMap(),
                ),
            )
        }

        /** HKDF-SHA256 (RFC 5869) — extract with a fixed salt, then expand once. */
        fun hkdf(ikm: ByteArray, info: ByteArray, len: Int): ByteArray {
            val salt = ByteArray(32) // all-zero salt is RFC-compliant
            val prk = hmac(salt, ikm)
            val out = ArrayList<Byte>(len)
            var t = ByteArray(0)
            var counter = 1
            while (out.size < len) {
                val mac = Mac.getInstance("HmacSHA256")
                mac.init(SecretKeySpec(prk, "HmacSHA256"))
                mac.update(t); mac.update(info); mac.update(counter.toByte())
                t = mac.doFinal()
                out.addAll(t.toList())
                counter++
            }
            return out.take(len).toByteArray()
        }
    }
}


/**
 * In-memory ratchet state, one instance per channel. Persisted by
 * [RatchetStateStore], which handles its own base64/JSON encoding (the [skipped]
 * map has a Long key, which the default kotlinx-json object encoder can't express,
 * so we serialize it explicitly there rather than annotating this class).
 */
data class RatchetState(
    val channelId: String,
    val sendChainKey: ByteArray,
    val recvChainKey: ByteArray,
    val sendCount: Long,
    val recvCount: Long,
    val skipped: Map<Long, ByteArray>,
) {
    // ByteArray in a data class gives by-reference equals/hashCode; we never
    // compare states for equality, so override to something stable + harmless.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = channelId.hashCode()
}

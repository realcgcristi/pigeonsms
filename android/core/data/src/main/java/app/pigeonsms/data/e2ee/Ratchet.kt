package app.pigeonsms.data.e2ee

import org.json.JSONObject
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class RatchetHeader(
    val v: Int = 1,
    val d: String,
    val k: String,
    val p: Long,
    val n: Long,
)

data class RatchetPacket(
    val header: RatchetHeader,
    val iv: String,
    val ciphertext: String,
)

data class RatchetState(
    val channelId: String,
    val localDeviceId: String,
    val remoteDeviceId: String,
    val rootKey: ByteArray,
    val selfPublicKey: ByteArray,
    val selfSecretKey: ByteArray,
    val remoteIdentity: ByteArray,
    val remoteKey: ByteArray,
    val sendChainKey: ByteArray,
    val receiveChainKey: ByteArray,
    val sendCount: Long,
    val receiveCount: Long,
    val previousSendCount: Long,
    val rotateBeforeSend: Boolean,
    val skipped: Map<String, ByteArray>,
    val skippedOrder: List<String>,
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = "$channelId:$remoteDeviceId".hashCode()
}

internal class Ratchet(private var state: RatchetState) {
    val snapshot: RatchetState get() = state

    fun encrypt(plaintext: String): RatchetPacket {
        if (state.rotateBeforeSend) rotateSending()
        val header = RatchetHeader(
            d = state.localDeviceId,
            k = Sodium.b64(state.selfPublicKey),
            p = state.previousSendCount,
            n = state.sendCount,
        )
        val (next, messageKey) = chainStep(state.sendChainKey)
        val sealed = Sodium.aesGcmSeal(messageKey, plaintext.toByteArray(Charsets.UTF_8), context(header))
        state = state.copy(sendChainKey = next, sendCount = state.sendCount + 1)
        return RatchetPacket(header, Sodium.b64(sealed.iv), Sodium.b64(sealed.ciphertext))
    }

    fun decrypt(packet: RatchetPacket): String {
        require(packet.header.v == 1 && packet.header.d == state.remoteDeviceId) { "encrypted message has the wrong sender" }
        val id = skippedId(Sodium.unb64(packet.header.k), packet.header.n)
        state.skipped[id]?.let { key ->
            val plain = open(key, packet)
            state = state.copy(
                skipped = state.skipped - id,
                skippedOrder = state.skippedOrder.filterNot { it == id },
            )
            return String(plain, Charsets.UTF_8)
        }
        if (!Sodium.unb64(packet.header.k).contentEquals(state.remoteKey)) rotateReceiving(packet.header)
        require(packet.header.n >= state.receiveCount) { "encrypted message was already consumed" }
        skipTo(packet.header.n)
        val (next, messageKey) = chainStep(state.receiveChainKey)
        val plain = open(messageKey, packet)
        state = state.copy(receiveChainKey = next, receiveCount = state.receiveCount + 1)
        return String(plain, Charsets.UTF_8)
    }

    private fun open(key: ByteArray, packet: RatchetPacket): ByteArray = Sodium.aesGcmOpen(
        key,
        Sodium.unb64(packet.iv),
        Sodium.unb64(packet.ciphertext),
        context(packet.header),
    )

    private fun context(header: RatchetHeader): ByteArray {
        val recipient = if (header.d == state.localDeviceId) state.remoteDeviceId else state.localDeviceId
        return ("open-pigeon-message-v1:${state.channelId}:${header.d}:$recipient:" + headerJson(header))
            .toByteArray(Charsets.UTF_8)
    }

    private fun rotateSending() {
        val next = Sodium.newBoxKeyPair()
        val (root, send) = rootStep(state.rootKey, Sodium.scalarMult(next.secretKey, state.remoteKey))
        state = state.copy(
            rootKey = root,
            selfPublicKey = next.publicKey,
            selfSecretKey = next.secretKey,
            sendChainKey = send,
            previousSendCount = state.sendCount,
            sendCount = 0,
            rotateBeforeSend = false,
        )
    }

    private fun rotateReceiving(header: RatchetHeader) {
        skipTo(header.p)
        val remote = Sodium.unb64(header.k)
        val (receiveRoot, receive) = rootStep(state.rootKey, Sodium.scalarMult(state.selfSecretKey, remote))
        val next = Sodium.newBoxKeyPair()
        val (sendRoot, send) = rootStep(receiveRoot, Sodium.scalarMult(next.secretKey, remote))
        state = state.copy(
            rootKey = sendRoot,
            selfPublicKey = next.publicKey,
            selfSecretKey = next.secretKey,
            remoteKey = remote,
            receiveChainKey = receive,
            sendChainKey = send,
            previousSendCount = state.sendCount,
            sendCount = 0,
            receiveCount = 0,
            rotateBeforeSend = false,
        )
    }

    private fun skipTo(target: Long) {
        require(target - state.receiveCount <= MAX_SKIP) { "too many skipped encrypted messages" }
        var chain = state.receiveChainKey
        var count = state.receiveCount
        val skipped = state.skipped.toMutableMap()
        val order = state.skippedOrder.toMutableList()
        while (count < target) {
            val (next, messageKey) = chainStep(chain)
            val id = skippedId(state.remoteKey, count)
            skipped[id] = messageKey
            order += id
            while (order.size > MAX_SKIP) skipped.remove(order.removeAt(0))
            chain = next
            count += 1
        }
        state = state.copy(receiveChainKey = chain, receiveCount = count, skipped = skipped, skippedOrder = order)
    }

    companion object {
        const val MAX_SKIP = 2_000
        private val zero = ByteArray(32)

        fun initialize(
            channelId: String,
            masterKey: ByteArray,
            localDeviceId: String,
            remoteDeviceId: String,
            localIdentity: Sodium.KeyPairBytes,
            remoteIdentity: ByteArray,
        ): Ratchet {
            val pair = listOf(localDeviceId, remoteDeviceId).sorted()
            val root = hkdf(masterKey, zero, "open-pigeon-pair-v1:$channelId:${pair[0]}:${pair[1]}", 32)
            val lowerToHigher = hkdf(root, zero, "open-pigeon-initial-lower-to-higher-v1", 32)
            val higherToLower = hkdf(root, zero, "open-pigeon-initial-higher-to-lower-v1", 32)
            val lower = localDeviceId < remoteDeviceId
            return Ratchet(
                RatchetState(
                    channelId = channelId,
                    localDeviceId = localDeviceId,
                    remoteDeviceId = remoteDeviceId,
                    rootKey = root,
                    selfPublicKey = localIdentity.publicKey,
                    selfSecretKey = localIdentity.secretKey,
                    remoteIdentity = remoteIdentity,
                    remoteKey = remoteIdentity,
                    sendChainKey = if (lower) lowerToHigher else higherToLower,
                    receiveChainKey = if (lower) higherToLower else lowerToHigher,
                    sendCount = 0,
                    receiveCount = 0,
                    previousSendCount = 0,
                    rotateBeforeSend = lower,
                    skipped = emptyMap(),
                    skippedOrder = emptyList(),
                ),
            )
        }

        fun headerJson(header: RatchetHeader): String =
            "{\"v\":1,\"d\":${JSONObject.quote(header.d)},\"k\":${JSONObject.quote(header.k)},\"p\":${header.p},\"n\":${header.n}}"

        fun chainStep(chainKey: ByteArray): Pair<ByteArray, ByteArray> =
            hmac(chainKey, byteArrayOf(2)) to hmac(chainKey, byteArrayOf(1)).copyOf(32)

        fun rootStep(root: ByteArray, shared: ByteArray): Pair<ByteArray, ByteArray> {
            val output = hkdf(shared, root, "open-pigeon-double-ratchet-root-v1", 64)
            return output.copyOfRange(0, 32) to output.copyOfRange(32, 64)
        }

        fun hkdf(input: ByteArray, salt: ByteArray, info: String, length: Int): ByteArray {
            val prk = hmac(salt, input)
            val output = ArrayList<Byte>(length)
            var previous = ByteArray(0)
            var counter = 1
            while (output.size < length) {
                previous = hmac(prk, previous + info.toByteArray(Charsets.UTF_8) + byteArrayOf(counter.toByte()))
                output.addAll(previous.toList())
                counter += 1
            }
            return output.take(length).toByteArray()
        }

        fun hmac(key: ByteArray, data: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(data)
        }

        private fun skippedId(publicKey: ByteArray, number: Long) = "${Sodium.b64(publicKey)}:$number"
    }
}

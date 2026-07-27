package app.pigeonsms.data.e2ee

import app.pigeonsms.db.RatchetStateDao
import app.pigeonsms.db.RatchetStateEntity
import org.json.JSONObject

/**
 * Persistence seam for per-channel [RatchetState] + the raw per-DM key and the
 * initiator flag (EXPERIMENTAL, flag-OFF).
 *
 * Backed by core/db's [RatchetStateDao] (A10) per the 2.8.0 contract. The
 * [RatchetStateEntity] carries only (channelId, stateBlob, updatedAt), so the DM
 * key and initiator flag are folded INTO [stateBlob] alongside the ratchet chains
 * rather than needing extra columns. stateBlob is opaque JSON with base64 fields.
 *
 * TODO(e2ee): the DM key living in the same at-rest blob as the ratchet state is
 * fine for v1 (the whole DB is app-private + can be encrypted via SQLCipher later),
 * but real-device review should confirm we never want to evict the DM key
 * independently of the ratchet.
 */
interface RatchetStateStore {
    suspend fun load(channelId: String): RatchetState?
    suspend fun save(state: RatchetState)
    suspend fun loadDmKey(channelId: String): ByteArray?
    suspend fun saveDmKey(channelId: String, key: ByteArray)
    suspend fun isInitiator(channelId: String): Boolean?
    suspend fun setInitiator(channelId: String, initiator: Boolean)
    suspend fun delete(channelId: String)
}

/**
 * Room-DAO-backed store. All three of {ratchet chains, DM key, initiator flag} for
 * a channel live in one [RatchetStateEntity.stateBlob] row keyed by channelId, so a
 * partial write (e.g. DM key known but ratchet not yet initialised) merges into the
 * existing row instead of clobbering it.
 */
internal class DaoRatchetStateStore(
    private val dao: RatchetStateDao,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : RatchetStateStore {

    override suspend fun load(channelId: String): RatchetState? {
        val o = readBlob(channelId) ?: return null
        val ratchet = o.optJSONObject("ratchet") ?: return null
        return decodeRatchet(channelId, ratchet)
    }

    override suspend fun save(state: RatchetState) {
        val o = readBlob(state.channelId) ?: JSONObject()
        o.put("ratchet", encodeRatchet(state))
        writeBlob(state.channelId, o)
    }

    override suspend fun loadDmKey(channelId: String): ByteArray? {
        val raw = readBlob(channelId)?.optString("dmKey")?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { Sodium.unb64(raw) }.getOrNull()
    }

    override suspend fun saveDmKey(channelId: String, key: ByteArray) {
        val o = readBlob(channelId) ?: JSONObject()
        o.put("dmKey", Sodium.b64(key))
        writeBlob(channelId, o)
    }

    override suspend fun isInitiator(channelId: String): Boolean? {
        val o = readBlob(channelId) ?: return null
        return if (o.has("initiator")) o.getBoolean("initiator") else null
    }

    override suspend fun setInitiator(channelId: String, initiator: Boolean) {
        val o = readBlob(channelId) ?: JSONObject()
        o.put("initiator", initiator)
        writeBlob(channelId, o)
    }

    override suspend fun delete(channelId: String) = dao.delete(channelId)

    private suspend fun readBlob(channelId: String): JSONObject? =
        dao.get(channelId)?.let { runCatching { JSONObject(it.stateBlob) }.getOrNull() }

    private suspend fun writeBlob(channelId: String, o: JSONObject) {
        dao.put(RatchetStateEntity(channelId = channelId, stateBlob = o.toString(), updatedAt = nowMs()))
    }

    private fun encodeRatchet(s: RatchetState): JSONObject {
        val skipped = JSONObject()
        s.skipped.forEach { (n, mk) -> skipped.put(n.toString(), Sodium.b64(mk)) }
        return JSONObject()
            .put("sendChainKey", Sodium.b64(s.sendChainKey))
            .put("recvChainKey", Sodium.b64(s.recvChainKey))
            .put("sendCount", s.sendCount)
            .put("recvCount", s.recvCount)
            .put("skipped", skipped)
    }

    private fun decodeRatchet(channelId: String, o: JSONObject): RatchetState {
        val skippedObj = o.optJSONObject("skipped") ?: JSONObject()
        val skipped = HashMap<Long, ByteArray>()
        skippedObj.keys().forEach { k -> skipped[k.toLong()] = Sodium.unb64(skippedObj.getString(k)) }
        return RatchetState(
            channelId = channelId,
            sendChainKey = Sodium.unb64(o.getString("sendChainKey")),
            recvChainKey = Sodium.unb64(o.getString("recvChainKey")),
            sendCount = o.getLong("sendCount"),
            recvCount = o.getLong("recvCount"),
            skipped = skipped,
        )
    }
}

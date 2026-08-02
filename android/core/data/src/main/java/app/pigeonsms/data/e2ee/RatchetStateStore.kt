package app.pigeonsms.data.e2ee

import app.pigeonsms.db.RatchetStateDao
import app.pigeonsms.db.RatchetStateEntity
import org.json.JSONArray
import org.json.JSONObject

data class ChannelMaster(
    val keyId: String,
    val key: ByteArray,
    val devices: List<DevicePub> = emptyList(),
)

interface RatchetStateStore {
    suspend fun load(channelId: String, remoteDeviceId: String): RatchetState?
    suspend fun save(state: RatchetState)
    suspend fun loadMaster(channelId: String): ChannelMaster?
    suspend fun listMasters(): Map<String, ChannelMaster>
    suspend fun saveMaster(channelId: String, master: ChannelMaster)
    suspend fun delete(channelId: String)
}

internal class DaoRatchetStateStore(
    private val dao: RatchetStateDao,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : RatchetStateStore {
    override suspend fun load(channelId: String, remoteDeviceId: String): RatchetState? =
        dao.get(ratchetId(channelId, remoteDeviceId))?.stateBlob?.let { value ->
            runCatching { decodeRatchet(JSONObject(value)) }.getOrNull()
        }

    override suspend fun save(state: RatchetState) {
        dao.put(
            RatchetStateEntity(
                channelId = ratchetId(state.channelId, state.remoteDeviceId),
                stateBlob = encodeRatchet(state).toString(),
                updatedAt = nowMs(),
            ),
        )
    }

    override suspend fun loadMaster(channelId: String): ChannelMaster? =
        dao.get(masterId(channelId))?.stateBlob?.let { value ->
            runCatching { decodeMaster(JSONObject(value)) }.getOrNull()
        }

    override suspend fun listMasters(): Map<String, ChannelMaster> = dao.masters().mapNotNull { row ->
        runCatching {
            row.channelId.removePrefix("master:") to decodeMaster(JSONObject(row.stateBlob))
        }.getOrNull()
    }.toMap()

    override suspend fun saveMaster(channelId: String, master: ChannelMaster) {
        val devices = JSONArray()
        master.devices.forEach { devices.put(JSONObject().put("id", it.deviceId).put("key", it.pubKeyBase64)) }
        val json = JSONObject()
            .put("keyId", master.keyId)
            .put("key", Sodium.b64(master.key))
            .put("devices", devices)
        dao.put(RatchetStateEntity(masterId(channelId), json.toString(), nowMs()))
    }

    override suspend fun delete(channelId: String) {
        dao.delete(masterId(channelId))
    }

    private fun encodeRatchet(state: RatchetState): JSONObject {
        val skipped = JSONObject()
        state.skipped.forEach { (id, key) -> skipped.put(id, Sodium.b64(key)) }
        return JSONObject()
            .put("channelId", state.channelId)
            .put("localDeviceId", state.localDeviceId)
            .put("remoteDeviceId", state.remoteDeviceId)
            .put("rootKey", Sodium.b64(state.rootKey))
            .put("selfPublicKey", Sodium.b64(state.selfPublicKey))
            .put("selfSecretKey", Sodium.b64(state.selfSecretKey))
            .put("remoteIdentity", Sodium.b64(state.remoteIdentity))
            .put("remoteKey", Sodium.b64(state.remoteKey))
            .put("sendChainKey", Sodium.b64(state.sendChainKey))
            .put("receiveChainKey", Sodium.b64(state.receiveChainKey))
            .put("sendCount", state.sendCount)
            .put("receiveCount", state.receiveCount)
            .put("previousSendCount", state.previousSendCount)
            .put("rotateBeforeSend", state.rotateBeforeSend)
            .put("skipped", skipped)
            .put("skippedOrder", JSONArray(state.skippedOrder))
    }

    private fun decodeRatchet(json: JSONObject): RatchetState {
        val skippedJson = json.optJSONObject("skipped") ?: JSONObject()
        val skipped = buildMap {
            skippedJson.keys().forEach { id -> put(id, Sodium.unb64(skippedJson.getString(id))) }
        }
        val orderJson = json.optJSONArray("skippedOrder") ?: JSONArray()
        val order = (0 until orderJson.length()).map { orderJson.getString(it) }
        return RatchetState(
            channelId = json.getString("channelId"),
            localDeviceId = json.getString("localDeviceId"),
            remoteDeviceId = json.getString("remoteDeviceId"),
            rootKey = Sodium.unb64(json.getString("rootKey")),
            selfPublicKey = Sodium.unb64(json.getString("selfPublicKey")),
            selfSecretKey = Sodium.unb64(json.getString("selfSecretKey")),
            remoteIdentity = Sodium.unb64(json.getString("remoteIdentity")),
            remoteKey = Sodium.unb64(json.getString("remoteKey")),
            sendChainKey = Sodium.unb64(json.getString("sendChainKey")),
            receiveChainKey = Sodium.unb64(json.getString("receiveChainKey")),
            sendCount = json.getLong("sendCount"),
            receiveCount = json.getLong("receiveCount"),
            previousSendCount = json.getLong("previousSendCount"),
            rotateBeforeSend = json.getBoolean("rotateBeforeSend"),
            skipped = skipped,
            skippedOrder = order,
        )
    }

    private fun decodeMaster(json: JSONObject): ChannelMaster {
        val list = json.optJSONArray("devices") ?: JSONArray()
        val devices = (0 until list.length()).map { index ->
            val item = list.getJSONObject(index)
            DevicePub(item.getString("id"), item.getString("key"))
        }
        return ChannelMaster(json.getString("keyId"), Sodium.unb64(json.getString("key")), devices)
    }

    private fun masterId(channelId: String) = "master:$channelId"
    private fun ratchetId(channelId: String, remoteDeviceId: String) = "ratchet:$channelId:$remoteDeviceId"
}

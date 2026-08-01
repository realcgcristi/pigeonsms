package app.pigeonsms.data

import android.content.Context
import app.pigeonsms.network.TransparencyCheckpointDto
import app.pigeonsms.network.TransparencyEntryDto
import app.pigeonsms.network.TransparencyResponse
import java.security.MessageDigest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray

data class PinnedTransparencyCheckpoint(val treeSize: Int, val rootHash: String)

object KeyTransparencyVerifier {
    fun verify(response: TransparencyResponse): Boolean {
        var previous: String? = null
        response.entries.forEach { entry ->
            if (entry.previous_hash != previous) return false
            if (hashEntry(entry) != entry.entry_hash) return false
            previous = entry.entry_hash
        }
        return response.checkpoint.tree_size == response.entries.size &&
            root(response.entries.map { it.entry_hash }) == response.checkpoint.root_hash
    }

    fun consistent(previous: PinnedTransparencyCheckpoint?, entries: List<TransparencyEntryDto>): Boolean {
        if (previous == null) return true
        if (entries.size < previous.treeSize) return false
        return root(entries.take(previous.treeSize).map { it.entry_hash }) == previous.rootHash
    }

    fun changed(previous: PinnedTransparencyCheckpoint?, current: TransparencyCheckpointDto): Boolean {
        if (previous == null) return false
        if (current.tree_size < previous.treeSize) return true
        return current.tree_size == previous.treeSize && current.root_hash != previous.rootHash
    }

    fun root(hashes: List<String>): String {
        if (hashes.isEmpty()) return hash("pigeon-empty-v1")
        var level = hashes
        while (level.size > 1) {
            level = level.chunked(2).map { pair ->
                val left = pair[0]
                val right = pair.getOrElse(1) { left }
                hash("pigeon-node-v1:$left:$right")
            }
        }
        return level.first()
    }

    private fun hashEntry(entry: TransparencyEntryDto): String {
        val canonical = buildJsonArray {
            add(entry.id)
            add(entry.user_id)
            add(entry.device_id)
            add(entry.action)
            if (entry.public_key == null) add(JsonNull) else add(entry.public_key)
            if (entry.previous_hash == null) add(JsonNull) else add(entry.previous_hash)
            add(entry.created_at)
        }.toString()
        return hash("pigeon-key-v1:$canonical")
    }

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

class TransparencyCheckpointStore(context: Context) {
    private val preferences = context.getSharedPreferences("pigeon_key_transparency", Context.MODE_PRIVATE)

    fun get(userId: String): PinnedTransparencyCheckpoint? {
        val root = preferences.getString("$userId.root", null) ?: return null
        val size = preferences.getInt("$userId.size", -1)
        return if (size >= 0) PinnedTransparencyCheckpoint(size, root) else null
    }

    fun put(userId: String, checkpoint: TransparencyCheckpointDto) {
        preferences.edit()
            .putInt("$userId.size", checkpoint.tree_size)
            .putString("$userId.root", checkpoint.root_hash)
            .apply()
    }
}

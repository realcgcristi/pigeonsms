package app.pigeonsms.data

import app.pigeonsms.network.TimeEventDto
import java.security.MessageDigest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray

object TimeMachineVerifier {
    fun verify(events: List<TimeEventDto>): Boolean {
        var previous: String? = null
        events.forEach { event ->
            if (event.previous_hash != previous) return false
            val canonical = buildJsonArray {
                add("pigeon-time-v1")
                add(event.id)
                add(event.space_id)
                add(event.sequence)
                add(event.kind)
                if (event.entity_id == null) add(JsonNull) else add(event.entity_id)
                if (event.actor_id == null) add(JsonNull) else add(event.actor_id)
                add(event.payload)
                add(event.created_at)
                if (previous == null) add(JsonNull) else add(previous)
            }.toString()
            val expected = MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            if (expected != event.event_hash) return false
            previous = expected
        }
        return true
    }
}

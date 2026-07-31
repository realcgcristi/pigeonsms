package app.pigeonsms.sdk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class PigeonBot(val client: PigeonClient) {
    private val handlers = mutableMapOf<String, suspend (BotInteraction) -> JsonObject>()
    private var active = false

    fun command(command: BotCommand, handler: suspend (BotInteraction) -> JsonObject): PigeonBot {
        handlers[command.name] = handler
        return this
    }

    suspend fun sync(commands: List<BotCommand>) = client.replaceCommands(commands)

    suspend fun run() {
        if (active) return
        active = true
        while (active) {
            try {
                for (interaction in client.pollInteractions()) {
                    val handler = handlers[interaction.command] ?: continue
                    val callback = interaction.callbackToken ?: continue
                    val response = runCatching { handler(interaction) }.getOrElse {
                        JsonObject(mapOf("type" to JsonPrimitive("message"), "content" to JsonPrimitive(it.message ?: "command failed"), "ephemeral" to JsonPrimitive(true)))
                    }
                    client.answerInteraction(interaction.id, callback, response)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                delay(1000)
            }
        }
    }

    fun stop() { active = false }
}

package app.pigeonsms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Handles notification actions away from the UI process.
 *
 * Android may start this receiver while the activity is not running, so all
 * work goes through the application's single AppContainer graph. goAsync()
 * keeps the process alive until the network/database operation completes.
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pendingResult = goAsync()
        val app = context.applicationContext as? PigeonApp
        if (app == null || intent == null) {
            pendingResult.finish()
            return
        }

        // RemoteInput results are attached to the broadcast intent. Extract
        // them before handing the work to a background coroutine.
        val replyResults = RemoteInput.getResultsFromIntent(intent)
        val reply = sequenceOf(KEY_TEXT_REPLY, "key_text_reply", "reply", "text")
            .mapNotNull { replyResults?.getCharSequence(it)?.toString()?.trim() }
            .firstOrNull { it.isNotEmpty() }

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    NOTIFICATION_ACTION_QUICK_REPLY -> {
                        if (reply != null) handleQuickReply(app.container, intent, reply)
                    }
                    NOTIFICATION_ACTION_MARK_READ -> handleMarkRead(app.container, intent)
                }
            } catch (_: Throwable) {
                // Notification actions are best-effort. A signed-out account,
                // expired token, or offline device must not crash the process.
            } finally {
                intent.getIntExtra(EXTRA_NOTIFICATION_ID, Int.MIN_VALUE)
                    .takeIf { it != Int.MIN_VALUE }
                    ?.let { id ->
                        app.getSystemService(android.app.NotificationManager::class.java)?.cancel(id)
                    }
                pendingResult.finish()
            }
        }
    }

    private suspend fun handleQuickReply(
        container: AppContainer,
        intent: Intent,
        reply: String,
    ) {
        val channelId = intent.getStringExtra(EXTRA_CHANNEL_ID)?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val session = container.sessionStore.session.first() ?: return
        val messageId = intent.getStringExtra(EXTRA_MESSAGE_ID)?.trim()?.takeIf { it.isNotEmpty() }
        // Route through ChatRepository so the reply is durable when the
        // device briefly loses connectivity; flush immediately when possible.
        container.chatRepository.send(
            channelId = channelId,
            content = reply,
            replyTo = messageId,
            attachment = null,
            selfName = session.username,
        )
        container.chatRepository.flushOutbox(channelId)
    }

    private suspend fun handleMarkRead(container: AppContainer, intent: Intent) {
        val channelId = intent.getStringExtra(EXTRA_CHANNEL_ID)?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val explicitSeq = intent.getLongExtra(EXTRA_MESSAGE_SEQ, -1L).takeIf { it > 0L }
        val seq = explicitSeq ?: intent.getStringExtra(EXTRA_MESSAGE_ID)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { messageId ->
                // The first page contains the just-pushed message in normal
                // operation. This also supports older payloads that omitted
                // message_seq while retaining message_id.
                container.api.messages(channelId).firstOrNull { it.id == messageId }?.seq
            }
        if (seq != null) container.chatRepository.markRead(channelId, seq)
    }
}

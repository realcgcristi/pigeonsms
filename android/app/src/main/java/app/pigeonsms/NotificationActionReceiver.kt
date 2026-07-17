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

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pendingResult = goAsync()
        val app = context.applicationContext as? PigeonApp
        if (app == null || intent == null) {
            pendingResult.finish()
            return
        }

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
                container.api.messages(channelId).firstOrNull { it.id == messageId }?.seq
            }
        if (seq != null) container.chatRepository.markRead(channelId, seq)
    }
}

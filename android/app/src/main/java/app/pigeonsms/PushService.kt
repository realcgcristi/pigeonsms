package app.pigeonsms

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

const val NOTIF_CHANNEL_MESSAGES = "messages"

const val PUSH_TOKEN_PREFS = "push_token"
const val PUSH_TOKEN_KEY = "fcm_token"
const val PUSH_TOKEN_SYNCED_KEY = "fcm_token_synced"

fun ensureNotificationChannel(context: Context) {
    val nm = context.getSystemService(NotificationManager::class.java) ?: return
    nm.createNotificationChannel(
        NotificationChannel(
            NOTIF_CHANNEL_MESSAGES,
            "Messages",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = "New messages from your friends" },
    )
    if (Build.VERSION.SDK_INT >= 26) {
        nm.getNotificationChannel(NOTIF_CHANNEL_MESSAGES)?.apply { setShowBadge(true) }
    }
}

class PushService : FirebaseMessagingService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {

        // or the device is offline, and losing a rotated token here means the
        // backend keeps pushing to a dead registration until the app next
        // fetches the token itself.
        val prefs = getSharedPreferences(PUSH_TOKEN_PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(PUSH_TOKEN_KEY, token).putBoolean(PUSH_TOKEN_SYNCED_KEY, false).apply()

        // no-op instead of crashing the service process.
        val container = (application as? PigeonApp)?.container ?: return
        scope.launch {
            val uploaded = runCatching { container.authRepository.registerPush(token) }.isSuccess
            if (uploaded) prefs.edit().putBoolean(PUSH_TOKEN_SYNCED_KEY, true).apply()
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val metadata = NotificationMetadata.from(data).let { parsed ->

            parsed.copy(
                title = parsed.title ?: message.notification?.title,
                body = parsed.body ?: message.notification?.body,
            )
        }
        val body = metadata.body ?: return
        val notificationPrefs = app.pigeonsms.data.NotificationPrefsStore(this)
        val mention = data["mention"] == "1" || data["mention"] == "true" || data["mentions"] == "1"
        val scoped = metadata.channelId?.let { notificationPrefs.get("channel", it) }
            ?: metadata.spaceId?.let { notificationPrefs.get("space", it) }
            ?: metadata.senderId?.let { notificationPrefs.get("user", it) }
            ?: notificationPrefs.get()
        if (scoped.mode == "mute" || (scoped.mode == "mentions" && !mention)) return

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        ensureNotificationChannel(this)
        val notificationId = nextNotificationId()
        val target = metadata.target()
        val open = PendingIntent.getActivity(this, notificationId, openIntent(target, metadata), pendingIntentFlags(mutable = false))
        val notif = NotificationCompat.Builder(this, NOTIF_CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(
                formatNotificationTitle(
                    spaceName = metadata.spaceName,
                    channelName = metadata.channelName,
                    senderUsername = metadata.senderUsername ?: metadata.senderDisplayName,
                    fallbackTitle = metadata.title ?: "PigeonSMS",
                ),
            )
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(open)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setSilent(!scoped.sound)
            .setVibrate(if (scoped.vibration) longArrayOf(0, 140, 60, 140) else longArrayOf(0))

        if (target != null) {
            val group = target.spaceId?.let { "space:$it:${target.channelId}" } ?: "channel:${target.channelId}"
            notif.setGroup(group)

            val replyIntent = Intent(this, NotificationActionReceiver::class.java)
                .setAction(NOTIFICATION_ACTION_QUICK_REPLY)
                .putNotificationTarget(target, notificationId)
            val replyPending = PendingIntent.getBroadcast(
                this,
                notificationId,
                replyIntent,
                pendingIntentFlags(mutable = true),
            )
            val remoteInput = RemoteInput.Builder(KEY_TEXT_REPLY)
                .setLabel("Reply")
                .build()
            notif.addAction(
                NotificationCompat.Action.Builder(
                    android.R.drawable.ic_menu_send,
                    "Reply",
                    replyPending,
                )
                    .addRemoteInput(remoteInput)
                    .setAllowGeneratedReplies(true)
                    .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                    .build(),
            )

            val readIntent = Intent(this, NotificationActionReceiver::class.java)
                .setAction(NOTIFICATION_ACTION_MARK_READ)
                .putNotificationTarget(target, notificationId)
            val readPending = PendingIntent.getBroadcast(
                this,
                notificationId + 1,
                readIntent,
                pendingIntentFlags(mutable = false),
            )
            notif.addAction(
                NotificationCompat.Action.Builder(
                    android.R.drawable.ic_menu_view,
                    "Mark as read",
                    readPending,
                )
                    .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
                    .build(),
            )
        }

        getSystemService(NotificationManager::class.java)
            ?.notify(notificationId, notif.build())
    }

    private fun openIntent(target: NotificationTarget?, metadata: NotificationMetadata): Intent =
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .also { intent ->
                if (target != null) {
                    intent.putNotificationTarget(target)
                    intent.data = Uri.parse("pigeonsms://channel/${Uri.encode(target.channelId)}")
                }

                // where only a preformatted title was sent.
                metadata.title?.let { intent.putExtra(EXTRA_NOTIFICATION_TITLE, it) }
            }

    @Suppress("DEPRECATION")
    private fun pendingIntentFlags(mutable: Boolean): Int {
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        flags = if (mutable) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags or PendingIntent.FLAG_MUTABLE else flags
        } else {
            flags or PendingIntent.FLAG_IMMUTABLE
        }
        return flags
    }
}

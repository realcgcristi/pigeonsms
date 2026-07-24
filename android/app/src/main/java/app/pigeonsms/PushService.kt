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

/**
 * Latest FCM registration token, persisted independently of the network
 * upload. onNewToken can fire while signed out or offline, and the upload in
 * [PushService.onNewToken] fails quietly in that case — the on-disk copy lets
 * app startup re-assert the token with the backend without waiting for
 * Firebase to mint a new one.
 */
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

/**
 * Receives FCM data messages ({title, body, kind:'sync'}) and posts them as
 * notifications. Token changes are re-registered with the backend (no-op when
 * signed out — the API call just fails quietly).
 */
class PushService : FirebaseMessagingService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        // Persist first: this callback also fires when the user is signed out
        // or the device is offline, and losing a rotated token here means the
        // backend keeps pushing to a dead registration until the app next
        // fetches the token itself.
        val prefs = getSharedPreferences(PUSH_TOKEN_PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(PUSH_TOKEN_KEY, token).putBoolean(PUSH_TOKEN_SYNCED_KEY, false).apply()

        // Firebase can be compiled in without a configured app (for example
        // a local/debug build with no google-services.json). Keep that path a
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
            // Notification messages from older Firebase senders may expose
            // title/body outside the data map. Preserve those as fallbacks.
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
        // POST_NOTIFICATIONS only exists as a runtime permission on 33+
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

            // Inline reply PendingIntents must be mutable so Android can add
            // the RemoteInput results to the broadcast intent on API 31+.
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

        // A monotonic process-local ID keeps same-body messages distinct.
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
                // Keep title/body available to MainActivity for old payloads
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

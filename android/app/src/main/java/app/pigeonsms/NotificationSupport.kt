package app.pigeonsms

import android.content.Intent
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicInteger

const val NOTIFICATION_ACTION_QUICK_REPLY = "app.pigeonsms.action.QUICK_REPLY"
const val NOTIFICATION_ACTION_MARK_READ = "app.pigeonsms.action.MARK_READ"
const val ACTION_QUICK_REPLY = NOTIFICATION_ACTION_QUICK_REPLY
const val ACTION_MARK_READ = NOTIFICATION_ACTION_MARK_READ

const val KEY_TEXT_REPLY = "app.pigeonsms.notification.reply"
const val NOTIFICATION_REMOTE_INPUT_KEY = KEY_TEXT_REPLY
const val REMOTE_INPUT_KEY = KEY_TEXT_REPLY

const val EXTRA_NOTIFICATION_ID = "notification_id"
const val EXTRA_CHANNEL_ID = "channel_id"
const val EXTRA_CHANNEL_NAME = "channel_name"
const val EXTRA_MESSAGE_ID = "message_id"
const val EXTRA_MESSAGE_SEQ = "message_seq"
const val EXTRA_SEQ = EXTRA_MESSAGE_SEQ
const val EXTRA_SPACE_ID = "space_id"
const val EXTRA_SPACE_NAME = "space_name"
const val EXTRA_SENDER_ID = "sender_id"
const val EXTRA_SENDER_USERNAME = "sender_username"
const val EXTRA_SENDER_DISPLAY_NAME = "sender_display_name"
const val EXTRA_NOTIFICATION_TITLE = "title"

data class NotificationTarget(
    val channelId: String,
    val channelName: String? = null,
    val messageId: String? = null,
    val messageSeq: Long? = null,
    val spaceId: String? = null,
    val spaceName: String? = null,
    val senderId: String? = null,
    val senderUsername: String? = null,
    val senderDisplayName: String? = null,
    val title: String? = null,
) {
    val chatTitle: String
        get() = channelName.clean() ?: senderDisplayName.clean() ?: senderUsername.clean() ?: "chat"
}

data class NotificationMetadata(
    val title: String? = null,
    val body: String? = null,
    val kind: String? = null,
    val channelId: String? = null,
    val channelName: String? = null,
    val messageId: String? = null,
    val messageSeq: Long? = null,
    val spaceId: String? = null,
    val spaceName: String? = null,
    val senderId: String? = null,
    val senderUsername: String? = null,
    val senderDisplayName: String? = null,
) {
    fun target(): NotificationTarget? {
        val id = channelId.clean() ?: return null
        return NotificationTarget(
            channelId = id,
            channelName = channelName.clean(),
            messageId = messageId.clean(),
            messageSeq = messageSeq,
            spaceId = spaceId.clean(),
            spaceName = spaceName.clean(),
            senderId = senderId.clean(),
            senderUsername = senderUsername.clean(),
            senderDisplayName = senderDisplayName.clean(),
            title = title.clean(),
        )
    }

    companion object {
        fun from(data: Map<String, String>): NotificationMetadata {
            fun value(vararg keys: String): String? = keys.firstNotNullOfOrNull { key -> data[key].clean() }
            val seq = value(EXTRA_MESSAGE_SEQ, "message_seq", "seq")?.toLongOrNull()
            return NotificationMetadata(
                title = value(EXTRA_NOTIFICATION_TITLE, "notification_title"),
                body = value("body", "message"),
                kind = value("kind"),
                channelId = value(EXTRA_CHANNEL_ID, "channelId"),
                channelName = value(EXTRA_CHANNEL_NAME, "channel", "channelName"),
                messageId = value(EXTRA_MESSAGE_ID, "messageId"),
                messageSeq = seq,
                spaceId = value(EXTRA_SPACE_ID, "spaceId"),
                spaceName = value(EXTRA_SPACE_NAME, "space", "spaceName"),
                senderId = value(EXTRA_SENDER_ID, "senderId"),
                senderUsername = value(EXTRA_SENDER_USERNAME, "sender", "username", "senderUsername"),
                senderDisplayName = value(EXTRA_SENDER_DISPLAY_NAME, "display_name", "senderDisplayName"),
            )
        }
    }
}

fun formatNotificationTitle(
    spaceName: String? = null,
    channelName: String? = null,
    senderUsername: String? = null,
    fallbackTitle: String? = null,
): String {
    var space = spaceName.clean()
    var channel = channelName.clean()
    var sender = senderUsername.clean()

    val fallback = fallbackTitle.clean()
    if ((space == null || channel == null || sender == null) && fallback != null) {
        val parts = fallback.split("•").map { it.trim() }
        if (parts.size >= 2 && parts[1].startsWith("#")) {
            if (space == null) space = parts[0]
            if (channel == null) channel = parts[1]
            if (sender == null && parts.getOrNull(2)?.startsWith("@") == true) sender = parts[2]
        }
    }

    val result = buildList {
        space?.let { add("[${it.stripBrackets()}]") }
        channel?.let { add("#${it.removePrefix("#").trim()}") }
        sender?.let { add("@${it.removePrefix("@").trim()}") }
    }.filter { it.length > 1 }

    return result.joinToString(" • ").ifBlank {
        fallback ?: "PigeonSMS"
    }
}

fun formatNotificationTitle(data: Map<String, String>, fallbackTitle: String? = null): String {
    val metadata = NotificationMetadata.from(data)
    return formatNotificationTitle(
        spaceName = metadata.spaceName,
        channelName = metadata.channelName,
        senderUsername = metadata.senderUsername ?: metadata.senderDisplayName,
        fallbackTitle = fallbackTitle ?: metadata.title,
    )
}

fun formatNotificationTitle(metadata: NotificationMetadata): String = formatNotificationTitle(
    spaceName = metadata.spaceName,
    channelName = metadata.channelName,
    senderUsername = metadata.senderUsername ?: metadata.senderDisplayName,
    fallbackTitle = metadata.title,
)

fun formatGroupNotificationTitle(
    spaceName: String?,
    channelName: String?,
    senderUsername: String?,
): String = formatNotificationTitle(spaceName, channelName, senderUsername)

private val notificationIdCounter = AtomicInteger(
    (SystemClock.elapsedRealtime() and 0x3FFFFFFF).toInt().coerceAtLeast(1),
)

fun nextNotificationId(): Int {
    var id = notificationIdCounter.incrementAndGet()
    if (id <= 0) {
        notificationIdCounter.set(1)
        id = notificationIdCounter.incrementAndGet()
    }
    return id
}

fun Intent.notificationTargetOrNull(): NotificationTarget? {
    fun string(vararg keys: String): String? {
        for (key in keys) {
            val fromExtra = extras?.get(key)?.toString().clean()
            if (fromExtra != null) return fromExtra
            val fromUri = data?.getQueryParameter(key).clean()
            if (fromUri != null) return fromUri
        }
        return null
    }

    val channelId = string(EXTRA_CHANNEL_ID, "channelId")
        ?: data
            ?.takeIf { it.scheme == "pigeonsms" && it.host == "channel" }
            ?.pathSegments
            ?.lastOrNull()
            ?.clean()
        ?: return null
    val seq = string(EXTRA_MESSAGE_SEQ, "seq")?.toLongOrNull()
    return NotificationTarget(
        channelId = channelId,
        channelName = string(EXTRA_CHANNEL_NAME, "channel", "channelName"),
        messageId = string(EXTRA_MESSAGE_ID, "messageId"),
        messageSeq = seq,
        spaceId = string(EXTRA_SPACE_ID, "spaceId"),
        spaceName = string(EXTRA_SPACE_NAME, "space", "spaceName"),
        senderId = string(EXTRA_SENDER_ID, "senderId"),
        senderUsername = string(EXTRA_SENDER_USERNAME, "sender", "username", "senderUsername"),
        senderDisplayName = string(EXTRA_SENDER_DISPLAY_NAME, "display_name", "senderDisplayName"),
        title = string(EXTRA_NOTIFICATION_TITLE, "notification_title"),
    )
}

fun Intent.putNotificationTarget(target: NotificationTarget, notificationId: Int? = null): Intent = apply {
    putExtra(EXTRA_CHANNEL_ID, target.channelId)
    target.channelName?.let { putExtra(EXTRA_CHANNEL_NAME, it) }
    target.messageId?.let { putExtra(EXTRA_MESSAGE_ID, it) }
    target.messageSeq?.let { putExtra(EXTRA_MESSAGE_SEQ, it) }
    target.spaceId?.let { putExtra(EXTRA_SPACE_ID, it) }
    target.spaceName?.let { putExtra(EXTRA_SPACE_NAME, it) }
    target.senderId?.let { putExtra(EXTRA_SENDER_ID, it) }
    target.senderUsername?.let { putExtra(EXTRA_SENDER_USERNAME, it) }
    target.senderDisplayName?.let { putExtra(EXTRA_SENDER_DISPLAY_NAME, it) }
    target.title?.let { putExtra(EXTRA_NOTIFICATION_TITLE, it) }
    if (notificationId != null) putExtra(EXTRA_NOTIFICATION_ID, notificationId)
}

private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

private fun String.stripBrackets(): String = trim().let {
    if (it.length >= 2 && it.first() == '[' && it.last() == ']') it.substring(1, it.length - 1).trim() else it
}.ifBlank { "Space" }

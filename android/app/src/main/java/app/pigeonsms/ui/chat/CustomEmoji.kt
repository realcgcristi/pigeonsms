package app.pigeonsms.ui.chat

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.pigeonsms.design.theme.Spacing
import app.pigeonsms.network.SpaceEmojiDto
import coil.compose.AsyncImage

/**
 * Custom nest emoji in the chat UI (2.9.5).
 *
 * Kept out of ChatScreen.kt on purpose — that file is already 4,100 lines, and
 * emoji rendering is self-contained enough that it doesn't need to live there.
 *
 * ## The `custom:` wire format
 *
 * A custom reaction is stored and transmitted as `custom:<emojiId>` rather than
 * the `:shortcode:`, so renaming an emoji doesn't orphan every reaction that used
 * it. Anything that renders a reaction therefore has to branch on the prefix,
 * which is what [isCustomReaction] and [customEmojiId] are for.
 */

private const val CUSTOM_PREFIX = "custom:"

/** True when a reaction value refers to a nest emoji rather than a Unicode glyph. */
fun isCustomReaction(value: String): Boolean = value.startsWith(CUSTOM_PREFIX)

/** The emoji id inside a `custom:<id>` reaction value. */
fun customEmojiId(value: String): String = value.removePrefix(CUSTOM_PREFIX)

/** Build the reaction value the API expects for a given nest emoji. */
fun customReactionValue(emoji: SpaceEmojiDto): String = "$CUSTOM_PREFIX${emoji.id}"

/**
 * One custom emoji, resolved to an image.
 *
 * Falls back to a placeholder when the emoji isn't in the nest's set — which
 * happens legitimately: an emoji can be deleted while old messages still carry
 * reactions pointing at it. A small marker is better than a broken image box, and
 * far better than crashing.
 */
@Composable
fun CustomEmojiImage(
    emoji: SpaceEmojiDto?,
    mediaUrl: (String) -> String?,
    modifier: Modifier = Modifier,
    size: Int = 20,
) {
    if (emoji == null) {
        Text("▫", style = MaterialTheme.typography.bodySmall)
        return
    }
    AsyncImage(
        model = mediaUrl(emoji.media_key),
        contentDescription = ":${emoji.name}:",
        contentScale = ContentScale.Fit,
        modifier = modifier.size(size.dp),
    )
}

/**
 * The custom-emoji strip inside the reaction picker.
 *
 * Renders nothing at all when the nest has no emoji (or the conversation is a DM,
 * which has no nest) — an empty labelled section would just be noise in a dialog
 * that's mostly one row of Unicode choices.
 */
@Composable
fun CustomEmojiPickerRow(
    emoji: List<SpaceEmojiDto>,
    selected: Set<String>,
    mediaUrl: (String) -> String?,
    onPick: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val inline = emoji.filter { it.kind == "emoji" }
    if (inline.isEmpty()) return

    Column(modifier.fillMaxWidth().padding(top = Spacing.m)) {
        Text(
            "this nest's emoji",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier.fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(top = Spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            inline.forEach { item ->
                val value = customReactionValue(item)
                val isSelected = value in selected
                val source = remember { MutableInteractionSource() }
                Surface(
                    onClick = { onPick(value, !isSelected) },
                    shape = CircleShape,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    interactionSource = source,
                    modifier = Modifier.size(44.dp).semantics {
                        contentDescription = ":${item.name}:"
                    },
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CustomEmojiImage(item, mediaUrl, size = 24)
                    }
                }
            }
        }
    }
}

/**
 * The sticker grid — same source table, different presentation: a sticker is sent
 * as a whole message rather than attached to one.
 */
@Composable
fun StickerPickerRow(
    emoji: List<SpaceEmojiDto>,
    mediaUrl: (String) -> String?,
    onPick: (SpaceEmojiDto) -> Unit,
    modifier: Modifier = Modifier,
) {
    val stickers = emoji.filter { it.kind == "sticker" }
    if (stickers.isEmpty()) return

    Column(modifier.fillMaxWidth().padding(top = Spacing.m)) {
        Text(
            "stickers",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier.fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(top = Spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            stickers.forEach { item ->
                val source = remember { MutableInteractionSource() }
                Surface(
                    onClick = { onPick(item) },
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    interactionSource = source,
                    modifier = Modifier.size(72.dp).semantics {
                        contentDescription = "sticker ${item.name}"
                    },
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CustomEmojiImage(item, mediaUrl, size = 60)
                    }
                }
            }
        }
    }
}

/**
 * A sticker message body (2.9.5).
 *
 * The server stamps `media_key` into the message metadata when the sticker is
 * sent, so this renders without needing the nest's emoji list loaded — which
 * matters because a sticker can arrive over the gateway before that list does.
 */
@Composable
fun StickerMessageContent(
    metadataJson: String?,
    mediaUrl: (String) -> String?,
    modifier: Modifier = Modifier,
) {
    val parsed = remember(metadataJson) {
        runCatching {
            val obj = org.json.JSONObject(metadataJson ?: "{}")
            obj.optString("media_key").takeIf { it.isNotBlank() } to
                obj.optString("alt", "sticker")
        }.getOrNull()
    }
    val key = parsed?.first
    if (key == null) {
        Text("sticker", style = MaterialTheme.typography.bodyMedium)
        return
    }
    AsyncImage(
        model = mediaUrl(key),
        contentDescription = parsed.second,
        contentScale = ContentScale.Fit,
        modifier = modifier.size(140.dp),
    )
}

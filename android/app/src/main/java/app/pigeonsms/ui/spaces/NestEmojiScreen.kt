package app.pigeonsms.ui.spaces

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.pigeonsms.design.theme.Spacing
import app.pigeonsms.ui.settings.SettingsSubHeader
import app.pigeonsms.network.SpaceEmojiDto
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manage a nest's custom emoji and stickers (2.9.5).
 *
 * Two-step creation, mirroring how message attachments already work: the image
 * goes to `/media/upload` first, then the returned key is registered as an emoji.
 * That keeps size limits and content-type sniffing in the one place that already
 * enforces them, and means a half-finished emoji is just an orphaned upload
 * rather than a broken row.
 *
 * Gated on MANAGE_EMOJI server-side; the screen is reachable from nest settings
 * for owners and admins, and the API rejects anyone else regardless of what the
 * client shows.
 */

/**
 * What the user may PICK. Generous, because rejecting someone's 4 MB PNG for
 * being 4 MB is a pointless wall when we can simply shrink it.
 */
private const val MAX_PICK_BYTES = 8 * 1024 * 1024

/**
 * What we UPLOAD after downscaling. Emoji render at ~20dp and stickers at ~140dp,
 * so anything past this is bytes every viewer pays for and nobody can see.
 *
 * The shrinking happens on-device on purpose: Cloudflare Workers has no image
 * library, so the server genuinely cannot resize (that needs the paid Cloudflare
 * Images product). Doing it here also saves the uploader's bandwidth.
 */
private const val TARGET_EMOJI_BYTES = 256 * 1024

/** Longest edge after downscaling — comfortably sharp for a sticker at 140dp. */
private const val MAX_EMOJI_EDGE = 320

private class PickedEmojiImage(val bytes: ByteArray, val type: String)

@Composable
fun NestEmojiScreen(
    spaceId: String,
    vm: NestEmojiViewModel,
    onBack: () -> Unit,
) {
    val ui by vm.ui.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var pendingImage by remember { mutableStateOf<PickedEmojiImage?>(null) }
    var pendingName by remember { mutableStateOf("") }
    var pendingKind by remember { mutableStateOf("emoji") }
    var deleteTarget by remember { mutableStateOf<SpaceEmojiDto?>(null) }

    LaunchedEffect(spaceId) { vm.load(spaceId) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { readEmojiImage(context, uri) } }
                    .onSuccess { picked ->
                        pendingImage = picked
                        pendingName = ""
                    }
                    .onFailure { error -> vm.reportError(error.message ?: "couldn't read that image") }
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = Spacing.m)) {
        // Shared skin-aware header (owns the status-bar inset + skin styling).
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(Modifier.weight(1f)) { SettingsSubHeader("nest emoji", onBack) }
            IconButton(
                onClick = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
            ) {
                Icon(Icons.Outlined.Add, contentDescription = "add emoji")
            }
        }
        Text(
            "png, gif or webp. big images are shrunk automatically; animated gifs must be under 256kb.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ui.error?.let { message ->
            Text(
                message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = Spacing.s),
            )
        }

        when {
            ui.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            ui.emoji.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "no emoji yet — tap + to make one",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(top = Spacing.m),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                items(ui.emoji, key = { it.id }) { emoji ->
                    Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp) {
                        Row(
                            Modifier.fillMaxWidth().padding(Spacing.s),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AsyncImage(
                                model = vm.mediaUrl(emoji.media_key),
                                contentDescription = ":${emoji.name}:",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.size(32.dp),
                            )
                            Column(Modifier.weight(1f)) {
                                Text(":${emoji.name}:", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    if (emoji.kind == "sticker") "sticker" else "emoji",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { deleteTarget = emoji }) {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = "delete :${emoji.name}:",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    pendingImage?.let { picked ->
        AlertDialog(
            onDismissRequest = { pendingImage = null },
            title = { Text("name this emoji") },
            text = {
                Column {
                    OutlinedTextField(
                        value = pendingName,
                        onValueChange = { typed ->
                            // Match the server's shortcode rule as you type, so an
                            // invalid name is impossible rather than rejected later.
                            pendingName = typed.lowercase()
                                .filter { ch -> ch.isLetterOrDigit() || ch == '_' }
                                .take(32)
                        },
                        label = { Text("shortcode") },
                        singleLine = true,
                    )
                    Row(
                        Modifier.padding(top = Spacing.s),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                    ) {
                        TextButton(onClick = { pendingKind = "emoji" }) {
                            Text(if (pendingKind == "emoji") "• emoji" else "emoji")
                        }
                        TextButton(onClick = { pendingKind = "sticker" }) {
                            Text(if (pendingKind == "sticker") "• sticker" else "sticker")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = pendingName.length >= 2 && !ui.busy,
                    onClick = {
                        vm.create(spaceId, pendingName, pendingKind, picked.bytes, picked.type) {
                            pendingImage = null
                            pendingName = ""
                        }
                    },
                ) { Text("add") }
            },
            dismissButton = { TextButton(onClick = { pendingImage = null }) { Text("cancel") } },
        )
    }

    deleteTarget?.let { emoji ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("delete :${emoji.name}:?") },
            text = { Text("Messages that used it keep their reaction, shown as a placeholder.") },
            confirmButton = {
                TextButton(onClick = { vm.delete(spaceId, emoji.id) { deleteTarget = null } }) {
                    Text("delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("cancel") } },
        )
    }
}

/**
 * Read a picked image, rejecting anything that isn't a usable emoji format or is
 * too large. Mirrors `readNestIconImage` — SVG is excluded for the same reason it
 * is everywhere else in this app: it isn't safe to render untrusted.
 */
private fun readEmojiImage(context: Context, uri: Uri): PickedEmojiImage {
    val resolver = context.contentResolver
    val type = resolver.getType(uri)?.substringBefore(';')?.trim()?.lowercase()
        ?.takeIf { it in setOf("image/png", "image/gif", "image/webp", "image/jpeg") }
        ?: throw IllegalArgumentException("emoji must be png, gif, webp or jpeg")

    val output = java.io.ByteArrayOutputStream(64 * 1024)
    resolver.openInputStream(uri)?.use { input ->
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_PICK_BYTES) throw IllegalArgumentException("pick an image under 8mb")
            output.write(buffer, 0, read)
        }
    } ?: throw IllegalArgumentException("couldn't open that image")
    val raw = output.toByteArray()
    if (raw.isEmpty()) throw IllegalArgumentException("that image is empty")

    // GIFs are left alone: decoding one to a Bitmap keeps only the first frame,
    // which would silently turn an animated emoji into a still. An oversized GIF
    // is rejected rather than quietly ruined.
    if (type == "image/gif") {
        if (raw.size > TARGET_EMOJI_BYTES) {
            throw IllegalArgumentException("animated gifs must be under 256kb — try a shorter or smaller one")
        }
        return PickedEmojiImage(raw, type)
    }

    if (raw.size <= TARGET_EMOJI_BYTES) return PickedEmojiImage(raw, type)
    return PickedEmojiImage(downscale(raw), "image/webp")
}

/**
 * Shrink a still image until it fits [TARGET_EMOJI_BYTES].
 *
 * Two stages: bound the longest edge (which does most of the work), then step
 * quality down if it's still too big. WebP because it beats PNG/JPEG at this size
 * and keeps transparency, which emoji need.
 */
private fun downscale(raw: ByteArray): ByteArray {
    val source = android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size)
        ?: throw IllegalArgumentException("couldn't read that image")
    val longest = maxOf(source.width, source.height)
    val scaled = if (longest <= MAX_EMOJI_EDGE) {
        source
    } else {
        val ratio = MAX_EMOJI_EDGE.toFloat() / longest
        android.graphics.Bitmap.createScaledBitmap(
            source,
            (source.width * ratio).toInt().coerceAtLeast(1),
            (source.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }

    val format = if (android.os.Build.VERSION.SDK_INT >= 30) {
        android.graphics.Bitmap.CompressFormat.WEBP_LOSSY
    } else {
        @Suppress("DEPRECATION")
        android.graphics.Bitmap.CompressFormat.WEBP
    }
    for (quality in intArrayOf(90, 80, 70, 60, 50)) {
        val out = java.io.ByteArrayOutputStream()
        scaled.compress(format, quality, out)
        if (out.size() <= TARGET_EMOJI_BYTES) return out.toByteArray()
    }
    throw IllegalArgumentException("couldn't shrink that image enough — try a simpler one")
}

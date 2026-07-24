package app.pigeonsms.ui.chat

import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.pigeonsms.design.theme.Corners
import app.pigeonsms.design.theme.PigeonMotion
import app.pigeonsms.design.theme.Spacing
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder

/** One image or video in the media timeline for a conversation. */
data class ConversationMedia(
    val messageId: String,
    val url: String,
    val name: String?,
    val type: String,
)

fun String.isConversationVideo(): Boolean = startsWith("video/", ignoreCase = true)

/** Inline embeds cap out here so a portrait video never dominates the chat column. */
private val INLINE_VIDEO_MAX_HEIGHT = 320.dp

/**
 * The app-wide Coil loader (PigeonApp) doesn't decode video; this shared loader
 * adds [VideoFrameDecoder] so thumbnails/posters can show the first frame.
 */
internal object VideoFrames {
    @Volatile private var loader: ImageLoader? = null
    fun loader(context: Context): ImageLoader = loader ?: synchronized(this) {
        loader ?: ImageLoader.Builder(context.applicationContext)
            .components { add(VideoFrameDecoder.Factory()) }
            .crossfade(true)
            .build()
            .also { loader = it }
    }
}

/**
 * One ExoPlayer per composed video, released with the composition. Reports the
 * real video aspect ratio so callers can size their frame to the media.
 */
@Composable
private fun rememberConversationPlayer(
    url: String,
    playWhenReady: Boolean,
    onAspectRatio: (Float) -> Unit = {},
    onError: () -> Unit = {},
): ExoPlayer {
    val context = LocalContext.current
    val currentOnAspectRatio by rememberUpdatedState(onAspectRatio)
    val currentOnError by rememberUpdatedState(onError)
    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            this.playWhenReady = playWhenReady
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    currentOnAspectRatio(
                        videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height,
                    )
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                currentOnError()
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    return player
}

@OptIn(UnstableApi::class)
@Composable
private fun ConversationPlayerSurface(
    player: ExoPlayer,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = true
                setShowNextButton(false)
                setShowPreviousButton(false)
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK)
            }
        },
        update = { view -> view.player = player },
        onRelease = { view -> view.player = null },
        modifier = modifier,
    )
}

/**
 * Inline video embed: poster frame + play button, tapping swaps in an ExoPlayer.
 * The frame follows the media aspect ratio (capped at [INLINE_VIDEO_MAX_HEIGHT])
 * and the player is released as soon as the message scrolls out of composition.
 */
@Composable
fun EmbeddedVideo(
    url: String,
    name: String?,
    modifier: Modifier = Modifier,
    onFullscreen: () -> Unit,
) {
    val context = LocalContext.current
    var playing by remember(url) { mutableStateOf(false) }
    var failed by remember(url) { mutableStateOf(false) }
    var ratio by remember(url) { mutableFloatStateOf(16f / 9f) }
    val playScale by animateFloatAsState(if (playing) 0f else 1f, PigeonMotion.snappy(), label = "playScale")

    Box(
        modifier
            .heightIn(max = INLINE_VIDEO_MAX_HEIGHT)
            .aspectRatio(ratio.coerceIn(0.42f, 2.6f))
            .clip(Corners.chip)
            .background(Color.Black),
    ) {
        if (playing && !failed) {
            val player = rememberConversationPlayer(
                url = url,
                playWhenReady = true,
                onAspectRatio = { ratio = it },
                onError = { failed = true },
            )
            ConversationPlayerSurface(player, Modifier.fillMaxSize())
        } else {
            AsyncImage(
                model = url,
                imageLoader = VideoFrames.loader(context),
                contentDescription = name ?: "video attachment",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.22f)))
        }

        if (playScale > 0.01f && !failed) {
            Surface(
                color = Color.Black.copy(alpha = 0.55f),
                shape = CircleShape,
                modifier = Modifier.align(Alignment.Center)
                    .graphicsLayer { scaleX = playScale; scaleY = playScale; alpha = playScale },
            ) {
                IconButton(onClick = { playing = true }, modifier = Modifier.size(56.dp)) {
                    Icon(
                        Icons.Outlined.PlayArrow,
                        name?.let { "play $it" } ?: "play video",
                        tint = Color.White,
                        modifier = Modifier.size(34.dp),
                    )
                }
            }
        }

        if (failed) {
            Text(
                "Video playback unavailable",
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.Center).padding(Spacing.m),
            )
        }

        Surface(
            color = Color.Black.copy(alpha = 0.55f),
            shape = Corners.chip,
            modifier = Modifier.align(Alignment.TopEnd).padding(Spacing.xs),
        ) {
            IconButton(onClick = onFullscreen, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Outlined.Fullscreen, name?.let { "Open $it fullscreen" } ?: "Open video fullscreen", tint = Color.White)
            }
        }
    }
}

/** Fullscreen swipeable image/video viewer for the current conversation. */
@Composable
fun ConversationMediaViewer(
    items: List<ConversationMedia>,
    initialIndex: Int,
    onDismiss: () -> Unit,
) {
    if (items.isEmpty()) return
    val safeInitial = initialIndex.coerceIn(0, items.lastIndex)
    val pager = rememberPagerState(initialPage = safeInitial) { items.size }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(
                state = pager,
                key = { page -> items[page].messageId },
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val item = items[page]
                if (item.type.isConversationVideo()) {
                    // settledPage: a page being dragged past never starts playback, and a
                    // small drag on the current page doesn't tear down the running player
                    FullscreenVideo(item, active = pager.settledPage == page)
                } else {
                    AsyncImage(
                        model = item.url,
                        contentDescription = item.name ?: "Conversation image ${page + 1}",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(vertical = 56.dp),
                    )
                }
            }

            Row(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(Spacing.m),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    val current = items.getOrNull(pager.currentPage)
                    Text(
                        current?.name ?: if (current?.type?.isConversationVideo() == true) "Video" else "Photo",
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                    )
                    Text(
                        "${pager.currentPage + 1} of ${items.size}",
                        color = Color.White.copy(alpha = 0.72f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, "Close media viewer", tint = Color.White)
                }
            }
        }
    }
}

/**
 * Video page in the fullscreen pager. The ExoPlayer only exists while this page
 * is the settled one — swiping away disposes (and therefore pauses + releases)
 * it; inactive pages show the poster frame instead.
 */
@Composable
private fun FullscreenVideo(item: ConversationMedia, active: Boolean) {
    val context = LocalContext.current
    var failed by remember(item.url) { mutableStateOf(false) }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (active && !failed) {
            val player = rememberConversationPlayer(
                url = item.url,
                playWhenReady = true,
                onError = { failed = true },
            )
            ConversationPlayerSurface(player, Modifier.fillMaxSize().padding(vertical = 56.dp))
        } else {
            AsyncImage(
                model = item.url,
                imageLoader = VideoFrames.loader(context),
                contentDescription = item.name ?: "video",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(vertical = 56.dp),
            )
        }
        if (failed) {
            Text(
                "Video playback unavailable",
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(Spacing.m),
            )
        }
    }
}

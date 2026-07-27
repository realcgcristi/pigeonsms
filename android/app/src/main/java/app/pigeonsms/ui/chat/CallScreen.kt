package app.pigeonsms.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.pigeonsms.design.theme.Spacing
import app.pigeonsms.ui.call.CallAudioController
import app.pigeonsms.ui.call.CallControlBar
import app.pigeonsms.ui.call.CallStatus
import app.pigeonsms.ui.call.CallTopOverlay
import app.pigeonsms.ui.call.WebRtcCallClient
import app.pigeonsms.ui.call.WebRtcEvent
import kotlinx.coroutines.delay
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

fun callPermissionsGranted(video: Boolean, context: android.content.Context): Boolean {
    val audio = androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val camera = !video || androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    return audio && camera
}

/** A remote peer's rendered video, tracked so we can attach/release renderers. */
private class RemoteTile(val peerId: String, val track: VideoTrack)

/**
 * Full-screen native WebRTC call surface. Media is captured with the platform
 * mic/camera via [WebRtcCallClient] (org.webrtc.*) — the WebView getUserMedia
 * path is gone because it failed with NotReadableError on some devices. Signaling
 * still speaks the CallRoom Durable Object protocol byte-for-byte. All controls,
 * status, and audio routing remain native.
 *
 * Signature is load-bearing — ChatScreen calls this exact shape.
 */
@Composable
fun CallScreenDialog(
    websocketUrl: String,
    video: Boolean,
    title: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    // Shared EGL context for all renderers + the encoder/decoder factories.
    // Created once; released in onDispose after all renderers are released.
    val eglBase = remember { EglBase.create() }

    var status by remember { mutableStateOf(CallStatus.Connecting) }
    var mediaReady by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    // Rolling on-screen diagnostics log — keep the last several phase/status/error
    // lines visible for the user to screenshot (we debug calls blind).
    val diagLog = remember { mutableStateListOf<String>() }
    fun logLine(line: String) {
        diagLog.add(line)
        while (diagLog.size > 8) diagLog.removeAt(0)
    }

    var muted by remember { mutableStateOf(false) }
    var cameraOff by remember { mutableStateOf(false) }
    var speakerOn by remember { mutableStateOf(video) } // video → speaker, voice → earpiece
    var durationSeconds by remember { mutableLongStateOf(0L) }
    var controlsVisible by remember { mutableStateOf(true) }
    var controlsShownAt by remember { mutableLongStateOf(0L) }

    // Remote peers with an active video track, keyed by peerId. Compose state so
    // the grid recomposes as peers join/leave. Voice calls never populate this.
    val remoteTiles = remember { mutableStateMapOf<String, RemoteTile>() }

    val audio = remember { CallAudioController(context) }

    // The native WebRTC engine. Callbacks arrive on WebRTC/ws threads → hop to main.
    val client = remember {
        WebRtcCallClient(
            appContext = context.applicationContext,
            websocketUrl = websocketUrl,
            video = video,
            eglBase = eglBase,
            onEvent = { event ->
                mainHandler.post {
                    when (event) {
                        is WebRtcEvent.Phase -> logLine(event.text)
                        is WebRtcEvent.Status -> {
                            status = event.status
                            when (event.status) {
                                CallStatus.Connecting -> logLine("• connecting")
                                CallStatus.Connected -> { errorMessage = null; logLine("• connected (peer up)") }
                                CallStatus.Reconnecting -> logLine("• reconnecting")
                                CallStatus.Ended -> logLine("• ended")
                            }
                        }
                        is WebRtcEvent.Error -> {
                            errorMessage = event.message
                            logLine("‼ ${event.message}")
                            controlsVisible = true
                        }
                        WebRtcEvent.MediaReady -> {
                            logLine("• media ok")
                            mediaReady = true
                        }
                    }
                }
            },
            onRemoteTrack = { peerId, track ->
                mainHandler.post { remoteTiles[peerId] = RemoteTile(peerId, track) }
            },
            onRemoteRemoved = { peerId ->
                mainHandler.post { remoteTiles.remove(peerId) }
            },
        )
    }

    val endCall = {
        client.release()
        onDismiss()
    }

    // Start capture + signaling once.
    LaunchedEffect(Unit) { client.start() }

    // Phone-call audio routing once the mic is live (grabbing MODE_IN_COMMUNICATION
    // earlier is what tripped NotReadableError on the WebView path; here we still
    // wait for mic capture before routing, matching the phone-call behavior).
    LaunchedEffect(mediaReady) {
        if (mediaReady) {
            audio.start(defaultSpeaker = video)
            audio.lastError?.let { logLine("‼ audio: $it") }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            audio.stop()
            client.release()
            // Renderers release themselves (see AndroidView onRelease); the shared
            // EGL context is released last, after all renderers are gone.
            eglBase.release()
        }
    }

    // Call duration ticker — counts while connected.
    LaunchedEffect(status) {
        if (status == CallStatus.Connected) {
            while (true) { delay(1_000); durationSeconds += 1 }
        }
    }

    // Auto-hide controls on video calls after 4s; tap re-shows them.
    LaunchedEffect(controlsShownAt, video) {
        if (video && controlsVisible) {
            delay(4_000)
            controlsVisible = false
        }
    }

    Dialog(
        onDismissRequest = endCall,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF0B0E16), Color(0xFF131827), Color(0xFF0B0E16)),
                    ),
                ),
        ) {
            if (video) {
                // Remote video tiles fill the surface; local preview floats.
                val tiles = remoteTiles.values.toList()
                if (tiles.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "waiting for video…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.5f),
                        )
                    }
                } else {
                    val columns = if (tiles.size <= 1) 1 else 2
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(columns),
                        modifier = Modifier.fillMaxSize().padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(tiles, key = { it.peerId }) { tile ->
                            VideoRenderer(
                                track = tile.track,
                                eglBase = eglBase,
                                mirror = false,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .aspectRatio(9f / 16f)
                                    .clip(RoundedCornerShape(20.dp)),
                            )
                        }
                    }
                }

                // Local self-preview, small, top-right.
                val localTrack = client.localVideoTrack
                if (localTrack != null && !cameraOff) {
                    VideoRenderer(
                        track = localTrack,
                        eglBase = eglBase,
                        mirror = client.isFrontCamera,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .statusBarsPadding()
                            .padding(top = 96.dp, end = 14.dp)
                            .width(104.dp)
                            .aspectRatio(104f / 150f)
                            .clip(RoundedCornerShape(18.dp)),
                    )
                }
            } else {
                // Voice call — avatar placeholder centered.
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .size(120.dp)
                            .clip(RoundedCornerShape(60.dp))
                            .background(Color.White.copy(alpha = 0.08f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            title.take(1).uppercase(),
                            style = MaterialTheme.typography.displaySmall,
                            color = Color.White.copy(alpha = 0.75f),
                        )
                    }
                }
            }

            // Tap layer for video calls — toggles the controls.
            if (video) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures {
                                controlsVisible = !controlsVisible
                                controlsShownAt = System.currentTimeMillis()
                            }
                        },
                )
            }

            CallTopOverlay(
                title = title,
                status = status,
                durationSeconds = durationSeconds,
                errorMessage = errorMessage,
                diagLog = diagLog,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = Spacing.m, start = Spacing.l, end = Spacing.l),
            )

            CallControlBar(
                visible = controlsVisible || !video,
                video = video,
                muted = muted,
                speakerOn = speakerOn,
                cameraOff = cameraOff,
                onToggleMute = {
                    muted = !muted
                    client.setMuted(muted)
                    controlsShownAt = System.currentTimeMillis()
                },
                onToggleSpeaker = {
                    speakerOn = !speakerOn
                    audio.setSpeaker(speakerOn)
                    controlsShownAt = System.currentTimeMillis()
                },
                onToggleCamera = {
                    cameraOff = !cameraOff
                    client.setCameraOff(cameraOff)
                    controlsShownAt = System.currentTimeMillis()
                },
                onSwitchCamera = {
                    client.switchCamera()
                    controlsShownAt = System.currentTimeMillis()
                },
                onEndCall = endCall,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = Spacing.xl),
            )
        }
    }
}

/**
 * A single WebRTC video tile. SurfaceViewRenderer must be init'd on the main
 * thread with the shared EGL context, and the sink added/removed to match the
 * view lifecycle so we never leak a track→renderer edge or double-release.
 */
@Composable
private fun VideoRenderer(
    track: VideoTrack,
    eglBase: EglBase,
    mirror: Boolean,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(eglBase.eglBaseContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                setEnableHardwareScaler(true)
                setMirror(mirror)
                runCatching { track.addSink(this) }
            }
        },
        update = { view -> view.setMirror(mirror) },
        onRelease = { view ->
            runCatching { track.removeSink(view) }
            view.release()
        },
    )
}

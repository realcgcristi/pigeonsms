package app.pigeonsms.ui.chat

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.pigeonsms.design.theme.Spacing
import app.pigeonsms.ui.call.CallAudioController
import app.pigeonsms.ui.call.CallControlBar
import app.pigeonsms.ui.call.CallStatus
import app.pigeonsms.ui.call.CallTopOverlay
import app.pigeonsms.ui.call.buildCallHtml
import kotlinx.coroutines.delay
import org.json.JSONObject

fun callPermissionsGranted(video: Boolean, context: android.content.Context): Boolean {
    val audio = androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val camera = !video || androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    return audio && camera
}

private const val CALL_ORIGIN = "https://appassets.androidplatform.net"
private const val CALL_PATH = "/call.html"

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CallScreenDialog(
    websocketUrl: String,
    video: Boolean,
    title: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val html = remember(websocketUrl, video) { buildCallHtml(websocketUrl, video) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    var status by remember { mutableStateOf(CallStatus.Connecting) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var muted by remember { mutableStateOf(false) }
    var cameraOff by remember { mutableStateOf(false) }
    var speakerOn by remember { mutableStateOf(video) } // video → speaker, voice → earpiece
    var durationSeconds by remember { mutableLongStateOf(0L) }
    var controlsVisible by remember { mutableStateOf(true) }
    var controlsShownAt by remember { mutableLongStateOf(0L) }

    val audio = remember { CallAudioController(context) }
    val webViewRef = remember { arrayOfNulls<WebView>(1) }
    fun js(script: String) = webViewRef[0]?.evaluateJavascript(script, null)

    val endCall = {
        js("window.pigeonCall && window.pigeonCall.end()")
        onDismiss()
    }

    val bridge = remember {
        object {
            @JavascriptInterface
            fun post(json: String) {
                mainHandler.post {
                    val m = runCatching { JSONObject(json) }.getOrNull() ?: return@post
                    when (m.optString("type")) {
                        "status" -> when (m.optString("state")) {
                            "connecting" -> status = CallStatus.Connecting
                            "connected" -> { status = CallStatus.Connected; errorMessage = null }
                            "reconnecting" -> status = CallStatus.Reconnecting
                            "ended" -> status = CallStatus.Ended
                        }
                        "error" -> { errorMessage = m.optString("message").ifBlank { "call error" }; controlsVisible = true }
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        audio.start(defaultSpeaker = video)
        onDispose {
            audio.stop()
            webViewRef[0]?.apply { loadUrl("about:blank"); destroy() }
            webViewRef[0] = null
        }
    }

    LaunchedEffect(status) {
        if (status == CallStatus.Connected) {
            while (true) { delay(1_000); durationSeconds += 1 }
        }
    }

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
        Box(Modifier.fillMaxSize().background(Color(0xFF0B0E16))) {
            AndroidView(
                factory = {
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.allowContentAccess = false
                        settings.allowFileAccess = false
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                                val url = request.url
                                if (url.host == "appassets.androidplatform.net") {
                                    return if (url.path == CALL_PATH) {
                                        WebResourceResponse("text/html", "utf-8", html.byteInputStream())
                                    } else {
                                        WebResourceResponse("text/plain", "utf-8", "".byteInputStream())
                                    }
                                }
                                return null
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onPermissionRequest(request: PermissionRequest) {
                                mainHandler.post {
                                    val allowed = request.resources.filter { resource ->
                                        resource == PermissionRequest.RESOURCE_AUDIO_CAPTURE ||
                                            (video && resource == PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                                    }.toTypedArray()
                                    if (allowed.isNotEmpty()) request.grant(allowed) else request.deny()
                                }
                            }
                        }
                        addJavascriptInterface(bridge, "PigeonNative")
                        webViewRef[0] = this
                        loadUrl(CALL_ORIGIN + CALL_PATH)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

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
                    js("window.pigeonCall && window.pigeonCall.setMuted(${muted})")
                    controlsShownAt = System.currentTimeMillis()
                },
                onToggleSpeaker = {
                    speakerOn = !speakerOn
                    audio.setSpeaker(speakerOn)
                    controlsShownAt = System.currentTimeMillis()
                },
                onToggleCamera = {
                    cameraOff = !cameraOff
                    js("window.pigeonCall && window.pigeonCall.setCamera(${!cameraOff})")
                    controlsShownAt = System.currentTimeMillis()
                },
                onSwitchCamera = {
                    js("window.pigeonCall && window.pigeonCall.switchCamera()")
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

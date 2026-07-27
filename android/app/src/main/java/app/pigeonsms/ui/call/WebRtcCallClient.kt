package app.pigeonsms.ui.call

import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * Native WebRTC call engine (org.webrtc.*). Replaces the WebView getUserMedia
 * path, which fails with NotReadableError on some devices even though native mic
 * capture works fine (voice messages record). All media is captured with the
 * platform mic/camera; signaling reuses the EXACT CallRoom Durable Object
 * protocol that the old in-WebView JS spoke, byte-for-byte:
 *
 *   inbound : ready {participant,participants[]}, join {participant},
 *             leave {participant}, offer {from,data:{type,sdp}},
 *             answer {from,data}, ice {from,data:{candidate,sdpMid,sdpMLineIndex}}
 *   outbound: {type:'offer',target,data:<sdp>}, {type:'answer',target,data},
 *             {type:'ice',target,data:<candidate>}
 *
 * Offer initiator rule mirrors the JS: the peer with the lexicographically
 * smaller selfId creates the offer (`if (selfId < peerId) createOffer`).
 *
 * ICE: Google STUN only. No TURN is available, so cross-NAT (symmetric NAT)
 * calls may fail to connect; same-network / cone-NAT calls will.
 *
 * Threading: PeerConnectionFactory / PeerConnection / capturer calls happen off
 * the UI thread here (a background coroutine scope); the caller must init/attach
 * SurfaceViewRenderers and read [remoteVideoTracks] on the main thread. Callbacks
 * from org.webrtc arrive on WebRTC's signaling thread — we marshal everything
 * user-visible through [onEvent]/[onRemoteTrack]/[onRemoteRemoved], which the
 * Compose layer re-dispatches to main.
 */
class WebRtcCallClient(
    private val appContext: Context,
    private val websocketUrl: String,
    private val video: Boolean,
    val eglBase: EglBase,
    /** Diagnostics/status line — mirrors the old JS `phase`/`status` output. */
    private val onEvent: (WebRtcEvent) -> Unit,
    /** A remote video track appeared for [peerId]. Called on any thread. */
    private val onRemoteTrack: (peerId: String, track: VideoTrack) -> Unit,
    /** A peer left / its connection died — drop its tile. Called on any thread. */
    private val onRemoteRemoved: (peerId: String) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val http = HttpClient(OkHttp) { install(WebSockets) }

    // Outbound signaling queue — decouples WebRTC callback threads from the ws.
    private val outbound = Channel<String>(Channel.UNLIMITED)
    @Volatile private var session: DefaultClientWebSocketSession? = null
    @Volatile private var ended = false
    @Volatile private var wsJob: Job? = null

    private lateinit var factory: PeerConnectionFactory
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var capturer: VideoCapturer? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var frontFacing = true

    /** Local camera preview track (null for voice calls). Read on main thread. */
    @Volatile var localVideoTrack: VideoTrack? = null
        private set

    private var selfId: String = ""
    private val peers = HashMap<String, PeerConnection>()
    private val lock = Any()

    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
    )

    // --- lifecycle -----------------------------------------------------------

    /** Build the factory + acquire local media, then connect signaling. Blocks
     *  briefly on the WebRTC init; safe to call from a coroutine. */
    fun start() {
        scope.launch {
            try {
                initFactory()
                acquireLocalMedia()
                onEvent(WebRtcEvent.MediaReady)
                connectSignaling()
            } catch (t: Throwable) {
                onEvent(WebRtcEvent.Error("setup failed: ${t.message ?: t.javaClass.simpleName}"))
            }
        }
    }

    private fun initFactory() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext)
                .createInitializationOptions(),
        )
        // Use the plain MIC source (same as the app's working voice-message recorder)
        // instead of WebRTC's default VOICE_COMMUNICATION, and turn off hardware
        // AEC/NS — on some devices those init paths fail and the mic never opens.
        val audioModule = JavaAudioDeviceModule.builder(appContext)
            .setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .createAudioDeviceModule()
        val encoder = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoder = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioModule)
            .setVideoEncoderFactory(encoder)
            .setVideoDecoderFactory(decoder)
            .createPeerConnectionFactory()
        onEvent(WebRtcEvent.Phase("webrtc factory ready"))
    }

    private fun acquireLocalMedia() {
        // Audio — always.
        audioSource = factory.createAudioSource(MediaConstraints())
        audioTrack = factory.createAudioTrack("pigeon-audio", audioSource).apply { setEnabled(true) }
        onEvent(WebRtcEvent.Phase("mic ok"))

        if (!video) return

        // Video — front camera by default.
        val enumerator = Camera2Enumerator(appContext)
        val deviceName = pickCamera(enumerator, front = true)
            ?: enumerator.deviceNames.firstOrNull()
        if (deviceName == null) {
            onEvent(WebRtcEvent.Phase("no camera found — audio only"))
            return
        }
        frontFacing = enumerator.isFrontFacing(deviceName)
        val cap = enumerator.createCapturer(deviceName, null)
        capturer = cap
        surfaceHelper = SurfaceTextureHelper.create("PigeonCapture", eglBase.eglBaseContext)
        val src = factory.createVideoSource(cap.isScreencast)
        videoSource = src
        cap.initialize(surfaceHelper, appContext, src.capturerObserver)
        cap.startCapture(1280, 720, 30)
        val track = factory.createVideoTrack("pigeon-video", src).apply { setEnabled(true) }
        videoTrack = track
        localVideoTrack = track
        onEvent(WebRtcEvent.Phase("camera ok (${if (frontFacing) "front" else "back"})"))
    }

    private fun pickCamera(enumerator: Camera2Enumerator, front: Boolean): String? =
        enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) == front }

    // --- signaling -----------------------------------------------------------

    private suspend fun connectSignaling() {
        var retries = 0
        while (!ended && scope.isActive) {
            try {
                onEvent(WebRtcEvent.Phase("connecting ws…"))
                http.webSocket(websocketUrl) {
                    session = this
                    retries = 0
                    onEvent(WebRtcEvent.Status(CallStatus.Connecting))
                    onEvent(WebRtcEvent.Phase("ws open — waiting for peer"))
                    // Pump outbound queue for the life of this session.
                    val sender = launch {
                        for (text in outbound) {
                            if (!isActive) break
                            runCatching { send(text) }
                        }
                    }
                    try {
                        for (frame in incoming) {
                            if (frame is Frame.Text) handleInbound(frame.readText())
                        }
                    } finally {
                        sender.cancel()
                        session = null
                    }
                }
            } catch (t: Throwable) {
                onEvent(WebRtcEvent.Phase("ws error: ${t.message ?: t.javaClass.simpleName}"))
            }
            if (ended) break
            // Signaling dropped — tear down peers, retry with backoff.
            dropAllPeers()
            if (retries < 5) {
                retries++
                onEvent(WebRtcEvent.Status(CallStatus.Reconnecting))
                delay(minOf(1000L * retries, 4000L))
            } else {
                onEvent(WebRtcEvent.Status(CallStatus.Ended))
                onEvent(WebRtcEvent.Error("signaling closed"))
                break
            }
        }
    }

    /** Enqueue outbound signaling JSON; safe from any (WebRTC callback) thread. */
    private fun signal(json: String) {
        if (!ended) outbound.trySend(json)
    }

    private fun handleInbound(raw: String) {
        val m = runCatching { JSONObject(raw) }.getOrNull() ?: return
        when (m.optString("type")) {
            "ready" -> {
                val self = m.optJSONObject("participant")
                selfId = self?.optString("userId").orEmpty()
                val others = m.optJSONArray("participants")
                var count = 0
                if (others != null) {
                    for (i in 0 until others.length()) {
                        val p = others.optJSONObject(i) ?: continue
                        val pid = p.optString("userId")
                        if (pid.isEmpty() || pid == selfId) continue
                        count++
                        // Smaller id creates the offer (mirrors old JS).
                        if (selfId < pid) createPeer(pid, offer = true)
                    }
                }
                onEvent(WebRtcEvent.Phase("ready — $count other participant(s)"))
            }
            "join" -> {
                val p = m.optJSONObject("participant") ?: return
                val pid = p.optString("userId")
                if (pid.isEmpty() || pid == selfId) return
                onEvent(WebRtcEvent.Phase("peer joined: ${p.optString("username").ifBlank { pid.take(6) }}"))
                if (selfId < pid) createPeer(pid, offer = true)
            }
            "leave" -> {
                val p = m.optJSONObject("participant") ?: return
                dropPeer(p.optString("userId"))
            }
            "offer" -> {
                val from = m.optString("from")
                val data = m.optJSONObject("data") ?: return
                val pc = createPeer(from, offer = false) ?: return
                val sdp = SessionDescription(SessionDescription.Type.OFFER, data.optString("sdp"))
                pc.setRemoteDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        pc.createAnswer(object : SimpleSdpObserver() {
                            override fun onCreateSuccess(desc: SessionDescription) {
                                pc.setLocalDescription(SimpleSdpObserver(), desc)
                                signal(
                                    JSONObject()
                                        .put("type", "answer")
                                        .put("target", from)
                                        .put("data", sdpToJson(desc))
                                        .toString(),
                                )
                            }
                        }, MediaConstraints())
                    }
                }, sdp)
            }
            "answer" -> {
                val from = m.optString("from")
                val data = m.optJSONObject("data") ?: return
                val pc = synchronized(lock) { peers[from] } ?: return
                val sdp = SessionDescription(SessionDescription.Type.ANSWER, data.optString("sdp"))
                pc.setRemoteDescription(SimpleSdpObserver(), sdp)
            }
            "ice" -> {
                val from = m.optString("from")
                val data = m.optJSONObject("data") ?: return
                val pc = createPeer(from, offer = false) ?: return
                val candidate = IceCandidate(
                    data.optString("sdpMid"),
                    data.optInt("sdpMLineIndex"),
                    data.optString("candidate"),
                )
                pc.addIceCandidate(candidate)
            }
            // mute / camera are informational for remote UI; we don't render
            // remote mute state, so nothing to do (kept for protocol parity).
        }
    }

    // --- peer connections ----------------------------------------------------

    private fun createPeer(peerId: String, offer: Boolean): PeerConnection? {
        if (peerId.isEmpty() || peerId == selfId) return null
        synchronized(lock) {
            peers[peerId]?.let { return it }
            val config = PeerConnection.RTCConfiguration(iceServers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy =
                    PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            }
            val pc = factory.createPeerConnection(
                config,
                PeerObserver(peerId),
            ) ?: return null
            // Attach local tracks. UNIFIED_PLAN: addTrack per track.
            audioTrack?.let { pc.addTrack(it, listOf("pigeon-stream")) }
            videoTrack?.let { pc.addTrack(it, listOf("pigeon-stream")) }
            peers[peerId] = pc
            if (offer) {
                pc.createOffer(object : SimpleSdpObserver() {
                    override fun onCreateSuccess(desc: SessionDescription) {
                        pc.setLocalDescription(SimpleSdpObserver(), desc)
                        signal(
                            JSONObject()
                                .put("type", "offer")
                                .put("target", peerId)
                                .put("data", sdpToJson(desc))
                                .toString(),
                        )
                    }
                }, MediaConstraints())
            }
            return pc
        }
    }

    private fun dropPeer(peerId: String) {
        if (peerId.isEmpty()) return
        val pc = synchronized(lock) { peers.remove(peerId) }
        pc?.let { runCatching { it.dispose() } }
        onRemoteRemoved(peerId)
    }

    private fun dropAllPeers() {
        val all = synchronized(lock) {
            val copy = peers.keys.toList()
            copy
        }
        all.forEach { dropPeer(it) }
    }

    private inner class PeerObserver(private val peerId: String) : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            signal(
                JSONObject()
                    .put("type", "ice")
                    .put("target", peerId)
                    .put(
                        "data",
                        JSONObject()
                            .put("candidate", candidate.sdp)
                            .put("sdpMid", candidate.sdpMid)
                            .put("sdpMLineIndex", candidate.sdpMLineIndex),
                    )
                    .toString(),
            )
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
        override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddStream(stream: MediaStream?) {}
        override fun onRemoveStream(stream: MediaStream?) {}
        override fun onDataChannel(channel: org.webrtc.DataChannel?) {}

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            onEvent(WebRtcEvent.Phase("ice ${peerId.take(6)}: $state"))
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            onEvent(WebRtcEvent.Phase("peer ${peerId.take(6)}: $newState"))
            when (newState) {
                PeerConnection.PeerConnectionState.CONNECTED ->
                    onEvent(WebRtcEvent.Status(CallStatus.Connected))
                PeerConnection.PeerConnectionState.FAILED,
                PeerConnection.PeerConnectionState.CLOSED,
                PeerConnection.PeerConnectionState.DISCONNECTED ->
                    dropPeer(peerId)
                else -> {}
            }
        }

        // Unified-plan track callback. Audio auto-plays via the audio device
        // module; video tracks are handed to the Compose layer for a renderer.
        override fun onTrack(transceiver: org.webrtc.RtpTransceiver?) {
            val track = transceiver?.receiver?.track() ?: return
            if (track is VideoTrack) {
                onEvent(WebRtcEvent.Phase("remote video from ${peerId.take(6)}"))
                onRemoteTrack(peerId, track)
            }
        }

        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
            val track = receiver?.track()
            if (track is VideoTrack) {
                onEvent(WebRtcEvent.Phase("remote video (addTrack) ${peerId.take(6)}"))
                onRemoteTrack(peerId, track)
            }
        }
    }

    // --- controls (called from UI) ------------------------------------------

    fun setMuted(muted: Boolean) {
        audioTrack?.setEnabled(!muted)
        signal(JSONObject().put("type", "mute").put("data", JSONObject().put("muted", muted)).toString())
    }

    fun setCameraOff(off: Boolean) {
        videoTrack?.setEnabled(!off)
        signal(JSONObject().put("type", "camera").put("data", JSONObject().put("off", off)).toString())
    }

    fun switchCamera() {
        val cap = capturer as? CameraVideoCapturer ?: return
        cap.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFront: Boolean) {
                frontFacing = isFront
            }
            override fun onCameraSwitchError(error: String?) {
                onEvent(WebRtcEvent.Phase("camera switch error: $error"))
            }
        })
    }

    /** True while the local front camera is active — the preview should mirror. */
    val isFrontCamera: Boolean get() = frontFacing

    // --- teardown ------------------------------------------------------------

    /** Fully release everything. Idempotent. Renderers are released by the UI. */
    fun release() {
        if (ended) return
        ended = true
        scope.launch {
            runCatching { session?.close() }
            session = null
        }
        outbound.close()
        // Stop capture first so no frames flow into a disposed source.
        runCatching { capturer?.stopCapture() }
        runCatching { capturer?.dispose() }
        runCatching { surfaceHelper?.dispose() }
        dropAllPeers()
        runCatching { videoTrack?.dispose() }
        runCatching { videoSource?.dispose() }
        runCatching { audioTrack?.dispose() }
        runCatching { audioSource?.dispose() }
        localVideoTrack = null
        runCatching { if (::factory.isInitialized) factory.dispose() }
        runCatching { http.close() }
        scope.cancel()
    }

    private fun sdpToJson(desc: SessionDescription): JSONObject =
        JSONObject().put("type", desc.type.canonicalForm()).put("sdp", desc.description)
}

/** Minimal SdpObserver so callers only override what they need. */
private open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(desc: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {}
    override fun onSetFailure(error: String?) {}
}

/** Events the engine emits to the Compose layer (which re-dispatches to main). */
sealed interface WebRtcEvent {
    /** Verbose diagnostics line — mirrors the old JS `phase`. */
    data class Phase(val text: String) : WebRtcEvent
    /** Connection lifecycle change. */
    data class Status(val status: CallStatus) : WebRtcEvent
    /** Fatal-ish error line for the on-screen log. */
    data class Error(val message: String) : WebRtcEvent
    /** Local mic (and camera, if video) captured — safe to start audio routing. */
    data object MediaReady : WebRtcEvent
}

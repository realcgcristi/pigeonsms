package app.pigeonsms.ui.call

import android.content.Context
import app.pigeonsms.network.PigeonApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule

class WebRtcCallClient(
    private val appContext: Context,
    private val api: PigeonApi,
    private val channelId: String,
    private val websocketUrl: String,
    private val video: Boolean,
    val eglBase: EglBase,
    private val onEvent: (WebRtcEvent) -> Unit,
    private val onRemoteTrack: (peerId: String, track: VideoTrack) -> Unit,
    private val onRemoteRemoved: (peerId: String) -> Unit,
) {
    private data class PeerState(
        val connection: PeerConnection,
        val pendingIce: MutableList<IceCandidate> = mutableListOf(),
        var remoteDescriptionSet: Boolean = false,
        var makingOffer: Boolean = false,
        var recoveryAttempt: Int = 0,
        var recoveryJob: Job? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val http = HttpClient(OkHttp) { install(WebSockets) }
    private val outbound = Channel<String>(256, BufferOverflow.DROP_OLDEST)
    private val lock = Any()
    private val peers = HashMap<String, PeerState>()

    @Volatile private var session: DefaultClientWebSocketSession? = null
    @Volatile private var ended = false
    @Volatile private var iceServers = fallbackIceServers()

    private lateinit var factory: PeerConnectionFactory
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var capturer: VideoCapturer? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var frontFacing = true
    private var selfId = ""
    private var signalingGeneration = 0
    private var configJob: Job? = null

    @Volatile var localVideoTrack: VideoTrack? = null
        private set

    fun start() {
        scope.launch {
            try {
                initFactory()
                acquireLocalMedia()
                onEvent(WebRtcEvent.MediaReady)
                val refreshDelay = refreshIceServers(false)
                configJob = scope.launch { refreshIceServersLoop(refreshDelay) }
                connectSignaling()
            } catch (error: Throwable) {
                onEvent(WebRtcEvent.Error("setup failed: ${error.message ?: error.javaClass.simpleName}"))
            }
        }
    }

    private fun initFactory() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext).createInitializationOptions(),
        )
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
        onEvent(WebRtcEvent.Phase("webrtc ready"))
    }

    private fun acquireLocalMedia() {
        audioSource = factory.createAudioSource(MediaConstraints())
        audioTrack = factory.createAudioTrack("pigeon-audio", audioSource).apply { setEnabled(true) }
        onEvent(WebRtcEvent.Phase("microphone ready"))
        if (video) acquireCamera()
    }

    private fun acquireCamera(): VideoTrack? {
        val enumerator = Camera2Enumerator(appContext)
        val deviceName = pickCamera(enumerator, true) ?: enumerator.deviceNames.firstOrNull()
        if (deviceName == null) {
            onEvent(WebRtcEvent.Phase("no camera available"))
            return null
        }
        frontFacing = enumerator.isFrontFacing(deviceName)
        val nextCapturer = enumerator.createCapturer(deviceName, null)
        capturer = nextCapturer
        surfaceHelper = SurfaceTextureHelper.create("PigeonCapture", eglBase.eglBaseContext)
        val source = factory.createVideoSource(nextCapturer.isScreencast)
        videoSource = source
        nextCapturer.initialize(surfaceHelper, appContext, source.capturerObserver)
        nextCapturer.startCapture(1280, 720, 30)
        val track = factory.createVideoTrack("pigeon-video", source).apply { setEnabled(true) }
        videoTrack = track
        localVideoTrack = track
        onEvent(WebRtcEvent.Phase("camera ready"))
        return track
    }

    fun enableVideo() {
        scope.launch {
            if (videoTrack != null) return@launch
            val track = acquireCamera() ?: return@launch
            val peerIds = synchronized(lock) {
                peers.forEach { (_, state) -> runCatching { state.connection.addTrack(track, listOf("pigeon-stream")) } }
                peers.keys.toList()
            }
            peerIds.forEach { createOffer(it, false) }
            signal(JSONObject().put("type", "camera").put("data", JSONObject().put("on", true)).toString())
        }
    }

    private fun pickCamera(enumerator: Camera2Enumerator, front: Boolean): String? =
        enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) == front }

    private suspend fun refreshIceServersLoop(initialDelay: Long) {
        var nextDelay = initialDelay
        while (!ended && scope.isActive) {
            delay(nextDelay)
            if (ended) return
            nextDelay = refreshIceServers(true)
        }
    }

    private suspend fun refreshIceServers(restartPeers: Boolean): Long {
        return runCatching {
            val config = api.callConfig(channelId)
            val next = config.ice_servers.flatMap { server ->
                server.urls.mapNotNull { url ->
                    runCatching {
                        PeerConnection.IceServer.builder(url).apply {
                            server.username?.let { setUsername(it) }
                            server.credential?.let { setPassword(it) }
                        }.createIceServer()
                    }.getOrNull()
                }
            }.ifEmpty { fallbackIceServers() }
            iceServers = next
            onEvent(WebRtcEvent.Phase(if (config.turn) "relay ready" else "direct connection mode"))
            if (restartPeers) {
                val peerIds = synchronized(lock) {
                    peers.forEach { (_, state) -> runCatching { state.connection.setConfiguration(rtcConfiguration()) } }
                    peers.keys.toList()
                }
                peerIds.forEach { createOffer(it, true) }
            }
            config.expires_at?.let { maxOf(60_000L, it - System.currentTimeMillis() - 300_000L) } ?: 300_000L
        }.getOrElse {
            onEvent(WebRtcEvent.Phase("relay configuration unavailable"))
            60_000L
        }
    }

    private suspend fun connectSignaling() {
        var attempt = 0
        while (!ended && scope.isActive) {
            try {
                onEvent(WebRtcEvent.Phase(if (attempt == 0) "connecting" else "restoring signaling"))
                http.webSocket(websocketUrl) {
                    session = this
                    signalingGeneration += 1
                    attempt = 0
                    onEvent(WebRtcEvent.Status(CallStatus.Connecting))
                    val sender = launch {
                        for (text in outbound) {
                            if (!isActive) break
                            if (runCatching { send(text) }.isFailure) {
                                outbound.trySend(text)
                                break
                            }
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
            } catch (error: Throwable) {
                if (!ended) onEvent(WebRtcEvent.Phase("signaling interrupted"))
            }
            if (ended) break
            onEvent(WebRtcEvent.Status(CallStatus.Reconnecting))
            val wait = minOf(1_000L * (1L shl minOf(attempt, 5)), 30_000L)
            attempt += 1
            delay(wait)
        }
    }

    private fun signal(json: String) {
        if (!ended) outbound.trySend(json)
    }

    private fun handleInbound(raw: String) {
        val message = runCatching { JSONObject(raw) }.getOrNull() ?: return
        when (message.optString("type")) {
            "ready" -> {
                selfId = message.optJSONObject("participant")?.optString("userId").orEmpty()
                val participants = message.optJSONArray("participants")
                var count = 0
                if (participants != null) {
                    for (index in 0 until participants.length()) {
                        val participant = participants.optJSONObject(index) ?: continue
                        val peerId = participant.optString("userId")
                        if (peerId.isEmpty() || peerId == selfId) continue
                        count += 1
                        createPeer(peerId)
                        if (signalingGeneration > 1 || selfId < peerId) createOffer(peerId, signalingGeneration > 1)
                    }
                }
                onEvent(WebRtcEvent.Phase("ready with $count peer(s)"))
            }
            "join" -> {
                val participant = message.optJSONObject("participant") ?: return
                val peerId = participant.optString("userId")
                if (peerId.isEmpty() || peerId == selfId) return
                createPeer(peerId)
                if (selfId < peerId) createOffer(peerId, false)
            }
            "leave" -> {
                val hadPeers = synchronized(lock) { peers.isNotEmpty() }
                dropPeer(message.optJSONObject("participant")?.optString("userId").orEmpty())
                val remaining = synchronized(lock) { peers.size }
                if (hadPeers && remaining == 0) onEvent(WebRtcEvent.AllPeersLeft)
            }
            "offer" -> acceptOffer(message)
            "answer" -> acceptAnswer(message)
            "ice" -> acceptIce(message)
            "declined" -> onEvent(WebRtcEvent.Declined)
            "missed" -> onEvent(WebRtcEvent.Missed)
        }
    }

    private fun acceptOffer(message: JSONObject) {
        val from = message.optString("from")
        val data = message.optJSONObject("data") ?: return
        var state = createPeer(from) ?: return
        val collision = synchronized(lock) {
            state.makingOffer || state.connection.signalingState() != PeerConnection.SignalingState.STABLE
        }
        if (collision && selfId < from) return
        if (collision) {
            dropPeer(from)
            state = createPeer(from) ?: return
        }
        val target = state
        val description = SessionDescription(SessionDescription.Type.OFFER, data.optString("sdp"))
        target.connection.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                markRemoteDescription(from, target)
                target.connection.createAnswer(object : SimpleSdpObserver() {
                    override fun onCreateSuccess(answer: SessionDescription) {
                        target.connection.setLocalDescription(object : SimpleSdpObserver() {
                            override fun onSetSuccess() {
                                signal(
                                    JSONObject()
                                        .put("type", "answer")
                                        .put("target", from)
                                        .put("data", sdpToJson(answer))
                                        .toString(),
                                )
                            }
                        }, answer)
                    }
                }, MediaConstraints())
            }
            override fun onSetFailure(error: String?) {
                scheduleRecovery(from, true)
            }
        }, description)
    }

    private fun acceptAnswer(message: JSONObject) {
        val from = message.optString("from")
        val data = message.optJSONObject("data") ?: return
        val state = synchronized(lock) { peers[from] } ?: return
        val description = SessionDescription(SessionDescription.Type.ANSWER, data.optString("sdp"))
        state.connection.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                markRemoteDescription(from, state)
            }
            override fun onSetFailure(error: String?) {
                scheduleRecovery(from, true)
            }
        }, description)
    }

    private fun acceptIce(message: JSONObject) {
        val from = message.optString("from")
        val data = message.optJSONObject("data") ?: return
        val state = createPeer(from) ?: return
        val candidate = IceCandidate(
            data.optString("sdpMid"),
            data.optInt("sdpMLineIndex"),
            data.optString("candidate"),
        )
        val addNow = synchronized(lock) {
            if (peers[from] !== state) return@synchronized false
            if (!state.remoteDescriptionSet) {
                if (state.pendingIce.size < 256) state.pendingIce += candidate
                false
            } else true
        }
        if (addNow) state.connection.addIceCandidate(candidate)
    }

    private fun markRemoteDescription(peerId: String, state: PeerState) {
        val pending = synchronized(lock) {
            if (peers[peerId] !== state) return
            state.remoteDescriptionSet = true
            state.pendingIce.toList().also { state.pendingIce.clear() }
        }
        pending.forEach(state.connection::addIceCandidate)
    }

    private fun rtcConfiguration() = PeerConnection.RTCConfiguration(iceServers).apply {
        sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
    }

    private fun createPeer(peerId: String): PeerState? {
        if (peerId.isEmpty() || peerId == selfId || ended) return null
        synchronized(lock) {
            peers[peerId]?.let { return it }
            val connection = factory.createPeerConnection(rtcConfiguration(), PeerObserver(peerId)) ?: return null
            audioTrack?.let { connection.addTrack(it, listOf("pigeon-stream")) }
            videoTrack?.let { connection.addTrack(it, listOf("pigeon-stream")) }
            return PeerState(connection).also { peers[peerId] = it }
        }
    }

    private fun createOffer(peerId: String, iceRestart: Boolean) {
        val state = synchronized(lock) {
            val current = peers[peerId] ?: createPeer(peerId) ?: return
            if (current.makingOffer || current.connection.signalingState() != PeerConnection.SignalingState.STABLE) return
            current.makingOffer = true
            current
        }
        val constraints = MediaConstraints().apply {
            if (iceRestart) mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
        }
        state.connection.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(description: SessionDescription) {
                state.connection.setLocalDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        synchronized(lock) { if (peers[peerId] === state) state.makingOffer = false }
                        signal(
                            JSONObject()
                                .put("type", "offer")
                                .put("target", peerId)
                                .put("data", sdpToJson(description))
                                .toString(),
                        )
                    }
                    override fun onSetFailure(error: String?) {
                        offerFailed(peerId, state)
                    }
                }, description)
            }
            override fun onCreateFailure(error: String?) {
                offerFailed(peerId, state)
            }
        }, constraints)
    }

    private fun offerFailed(peerId: String, state: PeerState) {
        synchronized(lock) { if (peers[peerId] === state) state.makingOffer = false }
        scheduleRecovery(peerId, false)
    }

    private fun scheduleRecovery(peerId: String, immediate: Boolean) {
        synchronized(lock) {
            val current = peers[peerId] ?: return
            if (current.recoveryJob?.isActive == true) return
            current.recoveryJob = scope.launch {
                val wait = if (immediate) 0L else minOf(1_500L * (1L shl minOf(current.recoveryAttempt, 5)), 30_000L)
                delay(wait)
                synchronized(lock) {
                    if (peers[peerId] !== current || ended) return@launch
                    current.recoveryAttempt += 1
                }
                onEvent(WebRtcEvent.Status(CallStatus.Reconnecting))
                createOffer(peerId, true)
                delay(10_000L)
                synchronized(lock) { if (peers[peerId] === current) current.recoveryJob = null }
                scheduleRecovery(peerId, false)
            }
        }
    }

    private fun dropPeer(peerId: String) {
        if (peerId.isEmpty()) return
        val state = synchronized(lock) { peers.remove(peerId) }
        state?.recoveryJob?.cancel()
        state?.connection?.let { runCatching { it.dispose() } }
        onRemoteRemoved(peerId)
    }

    private fun dropAllPeers() {
        synchronized(lock) { peers.keys.toList() }.forEach(::dropPeer)
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

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
        override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddStream(stream: MediaStream?) = Unit
        override fun onRemoveStream(stream: MediaStream?) = Unit
        override fun onDataChannel(channel: org.webrtc.DataChannel?) = Unit

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            onEvent(WebRtcEvent.Phase("ice ${peerId.take(6)}: $state"))
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            onEvent(WebRtcEvent.Phase("peer ${peerId.take(6)}: $newState"))
            when (newState) {
                PeerConnection.PeerConnectionState.CONNECTED -> {
                    synchronized(lock) {
                        peers[peerId]?.let {
                            it.recoveryJob?.cancel()
                            it.recoveryJob = null
                            it.recoveryAttempt = 0
                        }
                    }
                    onEvent(WebRtcEvent.Status(CallStatus.Connected))
                }
                PeerConnection.PeerConnectionState.DISCONNECTED -> scheduleRecovery(peerId, false)
                PeerConnection.PeerConnectionState.FAILED -> scheduleRecovery(peerId, true)
                PeerConnection.PeerConnectionState.CLOSED -> scope.launch { dropPeer(peerId) }
                else -> Unit
            }
        }

        override fun onTrack(transceiver: org.webrtc.RtpTransceiver?) {
            val track = transceiver?.receiver?.track()
            if (track is VideoTrack) onRemoteTrack(peerId, track)
        }

        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
            val track = receiver?.track()
            if (track is VideoTrack) onRemoteTrack(peerId, track)
        }
    }

    fun setMuted(muted: Boolean) {
        audioTrack?.setEnabled(!muted)
        signal(JSONObject().put("type", "mute").put("data", JSONObject().put("muted", muted)).toString())
    }

    fun setCameraOff(off: Boolean) {
        videoTrack?.setEnabled(!off)
        signal(JSONObject().put("type", "camera").put("data", JSONObject().put("off", off)).toString())
    }

    fun switchCamera() {
        val camera = capturer as? CameraVideoCapturer ?: return
        camera.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFront: Boolean) {
                frontFacing = isFront
            }
            override fun onCameraSwitchError(error: String?) {
                onEvent(WebRtcEvent.Phase("camera switch failed"))
            }
        })
    }

    val isFrontCamera: Boolean get() = frontFacing

    fun release() {
        if (ended) return
        ended = true
        configJob?.cancel()
        val activeSession = session
        session = null
        outbound.close()
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
        scope.launch {
            runCatching { activeSession?.close() }
            runCatching { http.close() }
            scope.cancel()
        }
    }

    private fun sdpToJson(description: SessionDescription): JSONObject =
        JSONObject().put("type", description.type.canonicalForm()).put("sdp", description.description)

    private fun fallbackIceServers() = listOf(
        PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
    )
}

private open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(desc: SessionDescription) = Unit
    override fun onSetSuccess() = Unit
    override fun onCreateFailure(error: String?) = Unit
    override fun onSetFailure(error: String?) = Unit
}

sealed interface WebRtcEvent {
    data class Phase(val text: String) : WebRtcEvent
    data class Status(val status: CallStatus) : WebRtcEvent
    data class Error(val message: String) : WebRtcEvent
    data object MediaReady : WebRtcEvent
    data object Declined : WebRtcEvent
    data object Missed : WebRtcEvent
    data object AllPeersLeft : WebRtcEvent
}

package app.pigeonsms.ui.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build

/**
 * Routes call audio like a phone call: MODE_IN_COMMUNICATION + voice-comm audio
 * focus, earpiece by default for voice calls, speakerphone for video calls.
 * [stop] restores whatever mode/routing the device had before the call.
 */
class CallAudioController(context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var previousMode = AudioManager.MODE_NORMAL
    private var previousSpeakerphone = false
    private var focusRequest: AudioFocusRequest? = null
    private var started = false

    /** Speaker state we last applied — the UI toggle reads this as its source of truth. */
    var speakerOn: Boolean = false
        private set

    /** Last audio error, if any — surfaced on-screen since we debug blind. Never fatal. */
    var lastError: String? = null
        private set

    fun start(defaultSpeaker: Boolean) {
        if (started) return
        started = true
        // Every platform call below can throw SecurityException / IllegalStateException on
        // odd OEM builds. Audio routing must never crash the call, so wrap each independently.
        runCatching {
            previousMode = audioManager.mode
            previousSpeakerphone = audioManager.isSpeakerphoneOn
        }.onFailure { lastError = "audio state read: ${it.message}" }

        runCatching {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener { /* WebRTC keeps flowing; nothing to pause */ }
                .build()
            audioManager.requestAudioFocus(request)
            focusRequest = request
        }.onFailure { lastError = "audio focus: ${it.message}" }

        runCatching { audioManager.mode = AudioManager.MODE_IN_COMMUNICATION }
            .onFailure { lastError = "audio mode: ${it.message}" }
        setSpeaker(defaultSpeaker)
    }

    fun setSpeaker(on: Boolean) {
        speakerOn = on
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val wanted = if (on) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                val device = audioManager.availableCommunicationDevices.firstOrNull { it.type == wanted }
                if (device != null) {
                    audioManager.setCommunicationDevice(device)
                    return@runCatching
                }
                // no earpiece on this device (tablet) → fall through to speakerphone flag
            }
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = on
        }.onFailure { lastError = "audio route: ${it.message}" }
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            }
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = previousSpeakerphone
            audioManager.mode = previousMode
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
        }
    }
}

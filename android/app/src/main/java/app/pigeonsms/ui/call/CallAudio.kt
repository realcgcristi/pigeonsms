package app.pigeonsms.ui.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build

class CallAudioController(context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var previousMode = AudioManager.MODE_NORMAL
    private var previousSpeakerphone = false
    private var focusRequest: AudioFocusRequest? = null
    private var started = false

    var speakerOn: Boolean = false
        private set

    var lastError: String? = null
        private set

    fun start(defaultSpeaker: Boolean) {
        if (started) return
        started = true

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
                .setOnAudioFocusChangeListener {  }
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

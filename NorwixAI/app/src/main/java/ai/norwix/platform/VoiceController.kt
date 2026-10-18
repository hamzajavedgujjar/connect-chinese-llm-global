package ai.norwix.platform

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import ai.norwix.domain.Preferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

data class VoiceChoice(val name: String, val label: String, val needsNetwork: Boolean)
data class VoiceState(
    val ready: Boolean = false,
    val listening: Boolean = false,
    val recognizing: Boolean = false,
    val speaking: Boolean = false,
    val partial: String = "",
    val voices: List<VoiceChoice> = emptyList(),
    val notice: String? = null
)

/** Owned by the ViewModel, but all platform speech calls run on the main thread. */
class VoiceController(private val context: Context) : AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private val state = MutableStateFlow(VoiceState())
    val status = state.asStateFlow()
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private val audio = requireNotNull(context.getSystemService(AudioManager::class.java))
    private var lastUtterance = ""
    private var activeSpeech = ""
    private var recognitionSession = 0L
    private var disposed = false
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener({ change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) stop()
        }, handler).build()

    init {
        tts = TextToSpeech(context) { result -> handler.post {
            if (disposed) return@post
            val engine = tts
            if (result == TextToSpeech.SUCCESS && engine != null) {
                engine.setAudioAttributes(attributes)
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { handler.post {
                        if (activeSpeech.isNotEmpty() && utteranceId?.startsWith(activeSpeech) == true) state.value = state.value.copy(speaking = true)
                    } }
                    override fun onDone(utteranceId: String?) { handler.post {
                        if (utteranceId == lastUtterance) { state.value = state.value.copy(speaking = false); audio.abandonAudioFocusRequest(focus) }
                    } }
                    @Deprecated("Android callback")
                    override fun onError(utteranceId: String?) { handler.post {
                        if (activeSpeech.isNotEmpty() && utteranceId?.startsWith(activeSpeech) == true) {
                            stop(); notice("The speech engine could not play this text.")
                        }
                    } }
                })
                val choices = engine.voices.orEmpty().filter { it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true }
                    .sortedWith(compareBy({ it.locale.toLanguageTag() }, { it.name }))
                    .map { VoiceChoice(it.name, "${it.locale.displayName} · ${it.name}", it.isNetworkConnectionRequired) }
                state.value = state.value.copy(ready = true, voices = choices)
            } else notice("No text-to-speech engine is ready. Install or enable one in Android settings.")
        } }
    }

    fun notice(message: String?) { state.value = state.value.copy(notice = message) }

    fun listen(preferences: Preferences, onResult: (String) -> Unit) {
        stop()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            notice("Microphone permission is required for voice input."); return
        }
        val local = preferences.onDeviceRecognitionOnly
        if (local && (Build.VERSION.SDK_INT < 31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(context))) {
            notice("On-device recognition is unavailable. Install a speech service or allow the system recognition service in Voice settings."); return
        }
        if (!local && !SpeechRecognizer.isRecognitionAvailable(context)) {
            notice("No speech recognition service is installed."); return
        }
        recognizer?.destroy()
        val session = System.nanoTime()
        recognitionSession = session
        recognizer = if (local && Build.VERSION.SDK_INT >= 31) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            else SpeechRecognizer.createSpeechRecognizer(context)
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { if (recognitionSession == session) state.value = state.value.copy(listening = true, recognizing = true) }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() { if (recognitionSession == session) state.value = state.value.copy(listening = false) }
            override fun onError(error: Int) {
                if (recognitionSession != session) return
                recognitionSession = 0L
                state.value = state.value.copy(listening = false, recognizing = false, partial = "")
                notice(when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech was recognized. Tap the microphone to try again."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone access was denied. Enable it in Android app settings."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "The speech service could not connect. Try on-device recognition if available."
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The speech service is busy. Stop it and try again."
                    else -> "The selected speech service could not recognize speech. Check its language packs and settings."
                })
            }
            override fun onResults(results: Bundle?) {
                if (recognitionSession != session) return
                recognitionSession = 0L
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                state.value = state.value.copy(listening = false, recognizing = false, partial = "")
                if (text.isNotBlank()) onResult(text) else notice("No speech was recognized.")
            }
            override fun onPartialResults(results: Bundle?) {
                if (recognitionSession != session) return
                state.value = state.value.copy(partial = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        state.value = state.value.copy(listening = true, recognizing = true, partial = "", notice = null)
        try {
            recognizer?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, preferences.speechLocale)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, local)
            })
        } catch (_: Exception) { stop(); notice("The speech service could not be started.") }
    }

    fun finishListening() { recognizer?.stopListening(); state.value = state.value.copy(listening = false) }

    fun speak(text: String, preferences: Preferences) {
        stop()
        val engine = tts
        if (!state.value.ready || engine == null) { notice("A text-to-speech engine is not ready."); return }
        val voice = engine.voices.orEmpty().firstOrNull {
            it.name == preferences.voiceName && (!preferences.offlineVoicesOnly || !it.isNetworkConnectionRequired)
        } ?: engine.voices.orEmpty().firstOrNull {
            it.locale.language == Locale.forLanguageTag(preferences.speechLocale).language &&
                (!preferences.offlineVoicesOnly || !it.isNetworkConnectionRequired) &&
                it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true
        }
        if (voice == null) { notice("No installed voice matches this language and privacy setting. Choose another voice or install a language pack."); return }
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            notice("Audio is in use by another app. Try playback again shortly."); return
        }
        if (engine.setVoice(voice) != TextToSpeech.SUCCESS) {
            audio.abandonAudioFocusRequest(focus)
            notice("The selected voice could not be activated. Choose another installed voice.")
            return
        }
        engine.setSpeechRate(preferences.speechRate.coerceIn(0.5f, 1.5f))
        val plain = text.replace(Regex("```[\\s\\S]*?```"), " Code block omitted. ")
            .replace(Regex("!?(\\[([^]]+)])\\([^)]+\\)"), "$2")
            .replace(Regex("[*_#`>]"), "").take(40_000)
        val pieces = plain.chunked(minOf(3000, TextToSpeech.getMaxSpeechInputLength()))
        if (pieces.isEmpty()) { audio.abandonAudioFocusRequest(focus); return }
        val session = System.nanoTime().toString()
        activeSpeech = session
        lastUtterance = "$session-${pieces.lastIndex}"
        state.value = state.value.copy(speaking = true, notice = null)
        pieces.forEachIndexed { index, piece ->
            if (engine.speak(piece, TextToSpeech.QUEUE_ADD, Bundle(), "$session-$index") == TextToSpeech.ERROR) {
                stop(); notice("The selected voice could not play this text."); return
            }
        }
    }

    fun stop() {
        activeSpeech = ""
        lastUtterance = ""
        recognitionSession = 0L
        recognizer?.cancel()
        tts?.stop()
        audio.abandonAudioFocusRequest(focus)
        state.value = state.value.copy(listening = false, recognizing = false, speaking = false, partial = "")
    }

    override fun close() {
        disposed = true
        stop()
        recognizer?.destroy(); recognizer = null
        tts?.shutdown(); tts = null
    }
}

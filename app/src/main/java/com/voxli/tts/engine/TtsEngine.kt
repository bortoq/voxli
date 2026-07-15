package com.voxli.tts.engine

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Android TTS wrapper with page-by-page auto-advance.
 *
 * Reference: roadmap §7.6 — TTS авто-листание страниц.
 *
 * @param context Android context
 * @param onPageFinished callback when TTS finishes speaking a page
 */
class TtsEngine(
    context: Context,
    private val onPageFinished: () -> Unit,
) {
    private var tts: TextToSpeech? = null

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _speed = MutableStateFlow(1.0f)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    private var initialized = false
    private var pendingPage: String? = null
    private var pendingPageTag: String? = null

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                initialized = true
                tts?.language = Locale("ru", "RU")
                tts?.setSpeechRate(_speed.value)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        _isSpeaking.value = false
                        onPageFinished()
                    }
                    override fun onError(utteranceId: String?) {
                        _isSpeaking.value = false
                    }
                })
                // Speak any pending page
                pendingPage?.let { speak(it, pendingPageTag ?: "") }
                pendingPage = null
                pendingPageTag = null
            }
        }
    }

    /**
     * Speak a page of text.
     * @param text plain text (from AnnotatedString.text)
     * @param tag unique utterance tag (e.g., "page_<N>")
     */
    fun speak(text: String, tag: String) {
        if (!initialized) {
            pendingPage = text
            pendingPageTag = tag
            return
        }

        tts?.stop()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, tag)
        _isSpeaking.value = true
    }

    /** Stop TTS. */
    fun stop() {
        tts?.stop()
        _isSpeaking.value = false
    }

    /** Set speech rate. 1.0 = normal, 0.5 = half, 2.0 = double. */
    fun setSpeed(rate: Float) {
        val clamped = rate.coerceIn(0.25f, 3.0f)
        _speed.value = clamped
        tts?.setSpeechRate(clamped)
    }

    /** Increase speed by 0.25. */
    fun speedUp() {
        setSpeed(_speed.value + 0.25f)
    }

    /** Decrease speed by 0.25. */
    fun speedDown() {
        setSpeed(_speed.value - 0.25f)
    }

    /** Release TTS resources. */
    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
    }
}

package com.raunakpatil.roniepocket.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Push-to-talk speech-to-text using Android's ON-DEVICE recognizer (Android 12+), so audio never leaves
 * the phone and there's nothing extra to download. If a phone has no on-device recognizer we report
 * "unavailable" instead of silently falling back to a cloud one. (whisper.cpp is the planned fallback.)
 * Must be used from the main thread.
 */
class Listener(private val ctx: Context, private val onPartial: (String) -> Unit, private val onFinal: (String) -> Unit,
               private val onEnd: (error: String?) -> Unit,
               /** Loudness 0..1 about 10× a second, for the voice sphere. */
               private val onLevel: (Float) -> Unit = {}) {
    companion object {
        fun available(ctx: Context): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)
    }

    private var rec: SpeechRecognizer? = null

    fun start() {
        if (!available(ctx)) { onEnd("On-device speech recognition isn't available on this phone."); return }
        stop()
        val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
        rec = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(b: Bundle?) { best(b)?.let(onPartial) }
            override fun onResults(b: Bundle?) { onFinal(best(b) ?: ""); onEnd(null); release() }
            override fun onError(error: Int) {
                Log.w("RonieVoice", "recognizer error $error")
                onEnd(when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Ronie needs microphone permission."
                    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ->
                        "The offline English speech pack isn't installed."
                    else -> "Speech recognition stopped (error $error)."
                })
                release()
            }
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            // rms is roughly -2 dB (silence) to 10 dB (speaking up close).
            override fun onRmsChanged(rms: Float) { onLevel(((rms + 2f) / 12f).coerceIn(0f, 1f)) }
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(t: Int, p: Bundle?) {}
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        })
    }

    /** Stop listening and use what was heard so far. */
    fun stop() { rec?.stopListening() }

    /** Stop listening and throw away what was heard. */
    fun cancel() { rec?.cancel(); release(); onEnd(null) }

    fun release() { rec?.destroy(); rec = null }

    private fun best(b: Bundle?) = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
}

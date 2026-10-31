package com.james.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * One-shot speech-to-text using the phone's built-in recogniser (Google on most phones).
 * Pauses the music while listening, so the mic hears the driver, then lets it carry on.
 * Main thread only.
 */
class VoiceInput(ctx: Context, private val callback: Callback) {

    interface Callback {
        fun onListening()
        fun onPartial(text: String)
        fun onResult(alternatives: List<String>)
        /** [reason]: "no-match", "no-permission", "unavailable", "network", "busy" or "error". */
        fun onError(reason: String)
    }

    private val app = ctx.applicationContext
    private val audio = app.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var active = false
    private var generation = 0L

    // Exclusive short focus: the music pauses while James listens, then resumes by itself.
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .build()

    // Safety net: some recognisers never answer when the network drops.
    private val timeout = Runnable {
        if (active) {
            Log.w(JamesService.TAG, "Speech recognition timed out")
            finish()
            callback.onError("no-match")
        }
    }

    fun hasPermission() =
        ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun start() {
        cancel()
        if (!hasPermission()) { callback.onError("no-permission"); return }
        if (!SpeechRecognizer.isRecognitionAvailable(app)) { callback.onError("unavailable"); return }

        val r = try {
            SpeechRecognizer.createSpeechRecognizer(app)
        } catch (e: Exception) {
            Log.e(JamesService.TAG, "Can't create speech recogniser: ${e.message}")
            callback.onError("unavailable")
            return
        }
        recognizer = r
        r.setRecognitionListener(listener(++generation))

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.packageName)
            // Give the driver a moment to think after "Where to?".
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }
        audio.requestAudioFocus(focus)
        active = true
        main.postDelayed(timeout, 12_000)
        try {
            r.startListening(intent)
        } catch (e: Exception) {
            Log.e(JamesService.TAG, "startListening failed: ${e.message}")
            finish()
            callback.onError("error")
        }
    }

    /** Stops listening without reporting anything. */
    fun cancel() {
        if (recognizer == null && !active) return
        finish()
    }

    private fun finish() {
        generation++
        active = false
        main.removeCallbacks(timeout)
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
        audio.abandonAudioFocusRequest(focus)
    }

    private fun listener(token: Long) = object : RecognitionListener {
        private fun isCurrent() = active && token == generation
        override fun onReadyForSpeech(params: Bundle?) { if (isCurrent()) callback.onListening() }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            if (!isCurrent()) return
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!text.isNullOrBlank()) callback.onPartial(text)
        }

        override fun onResults(results: Bundle?) {
            if (!isCurrent()) return
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.filter { it.isNotBlank() }.orEmpty()
            finish()
            if (list.isEmpty()) callback.onError("no-match") else callback.onResult(list)
        }

        override fun onError(error: Int) {
            if (!isCurrent()) return
            Log.w(JamesService.TAG, "Speech error $error")
            finish()
            callback.onError(
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no-match"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "no-permission"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                    SpeechRecognizer.ERROR_SERVER -> "network"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "busy"
                    else -> "error"
                }
            )
        }
    }
}

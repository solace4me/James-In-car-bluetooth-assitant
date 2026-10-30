package com.james.assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/**
 * Always-on "Hey James", using Vosk (free, open source, runs offline on the phone).
 *
 * Vosk is told it may only hear "hey james" or "[unk]" (anything else), which turns a full
 * speech recogniser into a wake-word spotter for any phrase, with no training or account.
 *
 * Only one app can use the microphone cleanly at a time, so James [pause]s this
 * while he listens for a command or an address, and [resume]s it after.
 * Main thread only.
 */
class WakeWord(
    ctx: Context,
    private val onWake: () -> Unit,
    private val onStatus: (listening: Boolean, status: String) -> Unit
) {
    private val app = ctx.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private var model: Model? = null
    private var loadingModel = false
    private var recognizer: Recognizer? = null
    private var service: SpeechService? = null
    private var running = false
    private var released = false
    private var lastWake = 0L

    /** What to say to wake James. */
    val phrase = "Hey James"

    /** Starts listening. Loads the model the first time (about a second, off the main thread). */
    fun start(): Boolean {
        if (released) return false
        if (running) return true
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            report(false, "Microphone not allowed")
            return false
        }
        val dir = VoiceModel.dir(app)
        if (dir == null) {
            report(false, "Download the voice model in James setup")
            return false
        }
        val m = model
        if (m == null) {
            if (!loadingModel) {
                loadingModel = true
                Thread {
                    try {
                        val loaded = Model(dir.absolutePath)
                        main.post {
                            loadingModel = false
                            if (released) loaded.close() else { model = loaded; start() }
                        }
                    } catch (e: Throwable) {
                        Log.e(JamesService.TAG, "Voice model failed to load", e)
                        main.post {
                            loadingModel = false
                            report(false, "Voice model failed to load. Download it again in setup.")
                        }
                    }
                }.start()
            }
            return true
        }
        return try {
            val rec = Recognizer(m, SAMPLE_RATE, GRAMMAR)
            val svc = SpeechService(rec, SAMPLE_RATE)
            svc.startListening(listener)
            recognizer = rec
            service = svc
            running = true
            report(true, "Listening for \"$phrase\"")
            true
        } catch (e: Throwable) {
            Log.e(JamesService.TAG, "Wake word couldn't start", e)
            closeListener()
            report(false, "Wake word couldn't start: ${e.message}")
            false
        }
    }

    private val listener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) = check(hypothesis, "partial")
        override fun onResult(hypothesis: String?) = check(hypothesis, "text")
        override fun onFinalResult(hypothesis: String?) {}
        override fun onError(exception: Exception?) {
            main.post {
                Log.e(JamesService.TAG, "Wake word error: ${exception?.message}")
                closeListener()
                report(false, "Wake word error: ${exception?.message}")
                resume(2000)   // try again shortly
            }
        }
        override fun onTimeout() {}
    }

    private fun check(json: String?, key: String) {
        if (json.isNullOrEmpty()) return
        val text = try { JSONObject(json).optString(key) } catch (_: Exception) { "" }
        if (text.isEmpty() || WAKE_PHRASES.none { text.contains(it) }) return
        main.post {
            val now = SystemClock.elapsedRealtime()
            if (!running || released || now - lastWake < 2500) return@post   // one wake per phrase
            lastWake = now
            Log.i(JamesService.TAG, "Heard \"$text\"")
            onWake()
        }
    }

    private val resumeRunnable = Runnable { if (!released && !running) start() }

    /** Stops listening and lets go of the mic (the model stays loaded), e.g. for speech recognition. */
    fun pause() {
        main.removeCallbacks(resumeRunnable)   // a resume that hasn't happened yet must not
        if (!running) return
        closeListener()
        report(false, "Paused")
    }

    /** Starts again after [pause]; a short delay lets the other mic user let go first. */
    fun resume(delayMs: Long = 400) {
        if (released || running) return
        main.removeCallbacks(resumeRunnable)
        main.postDelayed(resumeRunnable, delayMs)
    }

    fun release() {
        released = true
        main.removeCallbacksAndMessages(null)
        closeListener()
        try { model?.close() } catch (_: Throwable) {}
        model = null
    }

    private fun closeListener() {
        running = false
        try { service?.stop() } catch (_: Throwable) {}
        try { service?.shutdown() } catch (_: Throwable) {}
        service = null
        try { recognizer?.close() } catch (_: Throwable) {}
        recognizer = null
    }

    private fun report(listening: Boolean, status: String) {
        if (status != "Paused") Prefs.setWakeStatus(app, status)
        onStatus(listening, status)
    }

    companion object {
        private const val SAMPLE_RATE = 16000f
        /** Only these, or "[unk]" = anything else. Keeps false wakes from music and chatter low. */
        private const val GRAMMAR = "[\"hey james\", \"okay james\", \"[unk]\"]"
        private val WAKE_PHRASES = listOf("hey james", "okay james")
    }
}

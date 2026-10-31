package com.james.assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/**
 * Always-on "Hey Azarah", using Vosk (free, open source, runs offline on the phone).
 *
 * Vosk is told it may only hear a short phrase list (see [WakePhrase]), which turns a full
 * speech recogniser into a wake-word spotter, with no training or account. Only finished
 * utterances count (not Vosk's half-heard partial guesses), and only with good confidence.
 *
 * Only one app can use the microphone cleanly at a time, so James [pause]s this
 * while he listens for a command or an address, and [resume]s it after.
 * Main thread only.
 */
class WakeWord(
    ctx: Context,
    /** [command]: what was said after the wake word ("play the music"), or null to ask and listen. */
    private val onWake: (command: String?) -> Unit,
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
    /** Whether listening is wanted right now (false between pause() and resume()). */
    private var wanted = false
    private var lastWake = 0L
    private var listenerGeneration = 0L

    /** What to say to wake James. */
    val phrase = WakePhrase.PHRASE

    /** Starts listening. Loads the model the first time (about a second, off the main thread). */
    fun start(): Boolean {
        if (released) return false
        wanted = true
        if (running) return true
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            report(false, "Microphone not allowed")
            return false
        }
        val dir = VoiceModel.dir(app)
        if (dir == null) {
            report(false, "Download the voice model in Azarah setup")
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
                            when {
                                released -> loaded.close()
                                else -> {
                                    model = loaded
                                    // Only start if nobody paused us while the model was loading.
                                    if (wanted) start()
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        Log.e(JamesService.TAG, "Voice model failed to load", e)
                        main.post {
                            loadingModel = false
                            if (!released) report(false, "Voice model failed to load. Download it again in setup.")
                        }
                    }
                }.start()
            }
            return true
        }
        return try {
            val rec = Recognizer(m, SAMPLE_RATE, WakePhrase.grammarJson())
            recognizer = rec // retain immediately, including if SpeechService construction fails
            rec.setWords(true)   // per-word confidence, so weak guesses can be ignored
            val svc = SpeechService(rec, SAMPLE_RATE)
            service = svc
            listener = createListener(++listenerGeneration)
            running = true
            if (!svc.startListening(listener)) throw IllegalStateException("Wake listener did not start")
            report(true, "Listening for \"$phrase\"")
            true
        } catch (e: Throwable) {
            Log.e(JamesService.TAG, "Wake word couldn't start", e)
            closeListener()
            report(false, "Wake word couldn't start: ${e.message}")
            false
        }
    }

    private var listener = createListener(listenerGeneration)

    private fun isCurrent(token: Long) = token == listenerGeneration && running && wanted && !released

    private fun createListener(token: Long) = object : RecognitionListener {
        // Partial results are Vosk's half-heard guesses and change many times a second: acting on
        // them made background talk wake James. Only finished utterances count.
        override fun onPartialResult(hypothesis: String?) {}
        override fun onResult(hypothesis: String?) = check(hypothesis, token)
        override fun onFinalResult(hypothesis: String?) {}
        override fun onError(exception: Exception?) {
            main.post {
                if (!isCurrent(token)) return@post
                Log.e(JamesService.TAG, "Wake word error: ${exception?.message}")
                closeListener()
                report(false, "Wake word error: ${exception?.message}")
                resume(2000)   // try again shortly
            }
        }
        override fun onTimeout() {}
    }

    private fun check(json: String?, token: Long) {
        val words = WakePhrase.words(json)
        if (words.isEmpty() || words.all { it.text == "[unk]" }) return
        val match = WakePhrase.match(words)
        // Logged for tuning in the car: adb logcat -s James
        Log.d(JamesService.TAG, "Wake listener heard: " +
            words.joinToString(" ") { "${it.text}(${"%.2f".format(it.conf)})" } +
            if (match == null) " -> ignored" else " -> WAKE, command=${match.command}")
        if (match == null) return
        main.post {
            val now = SystemClock.elapsedRealtime()
            if (!isCurrent(token) || now - lastWake < 2500) return@post   // one wake per phrase
            lastWake = now
            onWake(match.command)
        }
    }

    private val resumeRunnable = Runnable { if (!released && !running) start() }

    /** Stops listening and lets go of the mic (the model stays loaded), e.g. for speech recognition. */
    fun pause() {
        wanted = false                         // also stops a model that's still loading from starting
        main.removeCallbacks(resumeRunnable)   // a resume that hasn't happened yet must not
        if (!running) { listenerGeneration++; return }
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
        wanted = false
        // Keep model-load completions queued: they own a native Model and must close it.
        main.removeCallbacks(resumeRunnable)
        closeListener()
        try { model?.close() } catch (_: Throwable) {}
        model = null
    }

    private fun closeListener() {
        listenerGeneration++
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
    }
}

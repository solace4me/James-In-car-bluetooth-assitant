package com.james.assistant

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * James's voice, using the phone's built-in text-to-speech (no extra download, no app size).
 * While James talks, it asks for "duck" audio focus, so the music dips and comes back after.
 */
class Speaker(ctx: Context) : TextToSpeech.OnInitListener {

    private val app = ctx.applicationContext
    private val audio = app.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attrs)
        .build()

    private var ready = false
    private var pending: Pair<String, (() -> Unit)?>? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val doneCallbacks = java.util.concurrent.ConcurrentHashMap<String, () -> Unit>()
    private var shutDown = false
    private var initFailed = false
    @Volatile private var lastId: String? = null

    // Created last: onInit can only run after the fields above exist.
    private val tts = TextToSpeech(app, this)

    override fun onInit(status: Int) {
        if (shutDown) return
        if (status != TextToSpeech.SUCCESS) {
            Log.w(JamesService.TAG, "Text-to-speech unavailable (status $status)")
            initFailed = true
            pending?.second?.let { cb -> main.post { cb() } }
            pending = null
            return
        }
        tts.setAudioAttributes(attrs)
        val lang = tts.setLanguage(Locale.getDefault())
        if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(Locale.UK)
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = finished(utteranceId)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finished(utteranceId)
            override fun onError(utteranceId: String?, errorCode: Int) = finished(utteranceId)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finished(utteranceId)
        })
        ready = true
        pending?.let { (text, done) -> say(text, done) }
        pending = null
    }

    /**
     * Speaks [text], replacing anything James was already saying.
     * [onDone] runs on the main thread when it has finished (or straight away if speech isn't available),
     * so James can start listening only after he has stopped talking.
     */
    fun say(text: String, onDone: (() -> Unit)? = null) {
        if (shutDown) return
        if (!ready) {
            if (initFailed) { onDone?.let { cb -> main.post { cb() } }; return }
            pending = text to onDone   // TTS still starting up: say it as soon as it's ready
            return
        }
        val id = "james-${System.nanoTime()}"
        lastId = id
        if (onDone != null) {
            doneCallbacks[id] = onDone
            // Safety net: some voice engines never report "done". Carry on anyway after a
            // generous guess at how long the sentence takes, so James never gets stuck.
            main.postDelayed({ finished(id) }, 3000L + text.length * 90L)
        }
        audio.requestAudioFocus(focus)
        val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        if (result != TextToSpeech.SUCCESS) finished(id)
    }

    private fun finished(id: String?) {
        // An older sentence cut off by a newer one must not end the newer one's ducking.
        if (id == lastId) release()
        val cb = id?.let { doneCallbacks.remove(it) } ?: return
        main.post { if (!shutDown) cb() }
    }

    private fun release() {
        audio.abandonAudioFocusRequest(focus)
    }

    fun shutdown() {
        shutDown = true
        doneCallbacks.clear()
        try {
            tts.stop()
            tts.shutdown()
        } catch (e: Exception) {
            Log.w(JamesService.TAG, "TTS shutdown: ${e.message}")
        }
        release()
    }
}

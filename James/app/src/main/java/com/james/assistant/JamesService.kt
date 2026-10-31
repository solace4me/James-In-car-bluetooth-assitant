package com.james.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import com.james.assistant.overlay.OverlayController

/**
 * The single foreground service that runs while the car is connected.
 * Milestone 1: start on connect, stop 10 s after disconnect.
 * Milestone 2: play Music/James_Default on connect; next / previous / play-pause
 *              from the notification, the car's steering wheel or earbud buttons.
 */
class JamesService : Service() {

    companion object {
        const val TAG = "James"
        const val ACTION_CONNECTED = "com.james.assistant.CONNECTED"
        const val ACTION_DISCONNECTED = "com.james.assistant.DISCONNECTED"
        const val ACTION_STOP = "com.james.assistant.STOP"
        const val ACTION_PREV = "com.james.assistant.PREV"
        const val ACTION_TOGGLE = "com.james.assistant.TOGGLE"
        const val ACTION_NEXT = "com.james.assistant.NEXT"
        const val ACTION_PLAY_FOLDER = "com.james.assistant.PLAY_FOLDER"
        const val EXTRA_FOLDER = "folder"
        const val ACTION_SHOW_OVERLAY = "com.james.assistant.SHOW_OVERLAY"
        /** James's window (sheet, bubble or setup) is on screen: a chance to unlock the mic. */
        const val ACTION_UI_VISIBLE = "com.james.assistant.UI_VISIBLE"
        /** Setup screen: the voice model or permissions changed, restart the wake word. */
        const val ACTION_WAKE_RELOAD = "com.james.assistant.WAKE_RELOAD"

        private const val CHANNEL_ID = "james_active"
        private const val NOTIFICATION_ID = 1
        private const val DISCONNECT_GRACE_MS = 10_000L

        @Volatile
        var isRunning = false
            private set

        /** What's playing, for the setup screen ("Song — Folder"), or null. */
        @Volatile
        var nowPlaying: String? = null
            private set

        /** Safe entry point used by the receiver, the presence service and the setup screen. */
        fun send(ctx: Context, action: String, reason: String, folder: String? = null) {
            Log.i(TAG, "send($action) from $reason")
            val intent = Intent(ctx, JamesService::class.java).setAction(action)
            if (folder != null) intent.putExtra(EXTRA_FOLDER, folder)
            try {
                when (action) {
                    ACTION_CONNECTED -> ContextCompat.startForegroundService(ctx, intent)
                    // stopService always works and removes the notification with the service.
                    ACTION_STOP -> {
                        isRunning = false
                        nowPlaying = null
                        ctx.stopService(Intent(ctx, JamesService::class.java))
                    }
                    else -> if (isRunning) ctx.startService(intent)
                }
            } catch (e: Exception) {
                // e.g. ForegroundServiceStartNotAllowedException if Android blocked a background start
                Log.e(TAG, "Could not send $action to JamesService: ${e.message}", e)
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val stopRunnable = Runnable {
        Log.i(TAG, "Car still gone after grace period, shutting down")
        shutDown()
    }

    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private var currentFolder: String? = null
    private var disconnectPending = false
    private var statusText = ""
    private var consecutiveErrors = 0
    private var playbackGeneration = 0L
    private var destroyed = false

    private fun invalidatePlaybackRequests() {
        playbackGeneration++
        audioWaitToken++
        overlay?.cancelPendingMusic()
    }

    private fun mayPlay() = !destroyed && isRunning && !disconnectPending

    private fun mayApplyPlayback(request: Long) = mayPlay() && request == playbackGeneration

    private fun pausePlayback() {
        invalidatePlaybackRequests()
        player?.pause()
    }

    private var speaker: Speaker? = null
    private var overlay: OverlayController? = null
    private var wake: WakeWord? = null
    private var wakeListening = false
    private var micBusyNow = false
    private var micRefreshed = false

    /** What the overlay's buttons do. */
    private val overlayActions = object : OverlayController.Actions {
        override fun playFolder(name: String) {
            playFolderNamed(name) { ok -> if (ok) speaker?.say("Playing ${OverlayController.spoken(name)}") }
        }
        override fun shuffleAll() {
            playFolderNamed(MusicLibrary.ALL_SONGS) { ok -> if (ok) speaker?.say("Shuffling all your songs") }
        }
        override fun currentFolder(): String? = this@JamesService.currentFolder
        override fun nowPlaying(): OverlayController.NowPlaying? {
            val p = player ?: return null
            val item = p.currentMediaItem ?: return null
            return OverlayController.NowPlaying(
                title = item.mediaMetadata.title?.toString() ?: "Unknown song",
                folder = this@JamesService.currentFolder ?: "",
                trackUri = item.localConfiguration?.uri,
                isPlaying = p.playWhenReady,
                positionMs = p.currentPosition,
                durationMs = if (p.duration == C.TIME_UNSET) 0L else p.duration
            )
        }
        override fun previous() { player?.seekToPrevious() }
        override fun togglePlay() = this@JamesService.togglePlay()
        override fun next() { player?.seekToNext() }
        override fun say(text: String, onDone: (() -> Unit)?) {
            val sp = speaker
            if (sp == null) onDone?.invoke() else sp.say(text, onDone)
        }
        override fun navigateTo(address: String): Boolean = this@JamesService.navigateTo(address)
        override fun listeningStarted() = ensureMicType()
        override fun micBusy(busy: Boolean) {
            // The wake word and the speech recogniser can't share the mic: take turns.
            micBusyNow = busy
            if (busy) wake?.pause() else wake?.resume()
        }
        override fun uiVisible() = refreshMicAccess()
        override fun play() {
            if (!mayPlay()) return
            val p = player
            if (p == null || p.mediaItemCount == 0) resumeOrStartDefault()
            else {
                invalidatePlaybackRequests()
                if (p.playbackState == Player.STATE_IDLE) p.prepare()
                p.play()
            }
        }
        override fun pause() = pausePlayback()
        override fun statusLine(): String = when {
            disconnectPending -> "Car disconnected"
            statusText.startsWith("Waiting") -> "Waiting for car audio…"
            wakeListening -> "Say \"${wake?.phrase ?: WakePhrase.PHRASE}\" any time"
            else -> "Connected to ${Prefs.carName(this@JamesService) ?: "your car"}"
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Android requires startForeground() soon after startForegroundService(). Doing it here,
        // before anything else, means even an instant "Stop" can't trip that rule and crash James.
        statusText = "Starting…"
        goForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_CONNECTED   // null = restarted by the system
        Log.i(TAG, "onStartCommand: $action")

        when (action) {
            ACTION_STOP -> {
                shutDown()
                return START_NOT_STICKY
            }

            ACTION_CONNECTED -> {
                if (destroyed) return START_NOT_STICKY
                val firstStart = !isRunning
                val wasDisconnecting = disconnectPending
                if (firstStart || wasDisconnecting) {
                    statusText = "Connected to ${Prefs.carName(this) ?: "your car"}"
                }
                isRunning = true
                disconnectPending = false
                handler.removeCallbacks(stopRunnable)
                Parking.cancelPending()             // a location request from a quick glitch must not land
                Parking.cancelNotification(this)   // back in the car: "find my car" no longer needed
                // Must be called promptly after every startForegroundService().
                if (!goForeground()) return START_NOT_STICKY

                if (firstStart) {
                    Toast.makeText(applicationContext, "Azarah is active", Toast.LENGTH_SHORT).show()
                    if (speaker == null) speaker = Speaker(this)
                    showOverlay()
                    startWakeWord()
                    whenCarAudioReady { resumeOrStartDefault() }
                } else {
                    overlay?.ensureVisible()
                }
                if (!firstStart && wasDisconnecting) {
                    // Car came back within 10 s: carry on (or start, if music never got going).
                    whenCarAudioReady {
                        val p = player
                        if (p != null && p.mediaItemCount > 0) {
                            invalidatePlaybackRequests()
                            p.play()
                        } else resumeOrStartDefault()
                    }
                }
            }

            ACTION_DISCONNECTED -> {
                if (!isRunning) { stopSelf(); return START_NOT_STICKY }
                // First disconnect of this stop: remember where the car is parked.
                if (!disconnectPending) Parking.capture(this)
                disconnectPending = true
                pausePlayback()   // cancel queued scans as well as "waiting for car audio"
                saveResumePoint()
                statusText = "${Prefs.carName(this) ?: "Car"} disconnected. Stopping in 10 seconds…"
                updateNotification()
                handler.removeCallbacks(stopRunnable)
                handler.postDelayed(stopRunnable, DISCONNECT_GRACE_MS)
            }

            else -> {
                // Music controls. Ignore them if James isn't running (e.g. a stale notification).
                if (!isRunning) { stopSelf(); return START_NOT_STICKY }
                when (action) {
                    ACTION_PREV -> player?.seekToPrevious()
                    ACTION_NEXT -> player?.seekToNext()
                    ACTION_TOGGLE -> togglePlay()
                    ACTION_PLAY_FOLDER -> intent?.getStringExtra(EXTRA_FOLDER)?.let { playFolderNamed(it) }
                    ACTION_SHOW_OVERLAY -> showOverlay()
                    ACTION_UI_VISIBLE -> refreshMicAccess()
                    ACTION_WAKE_RELOAD -> {
                        wake?.release()
                        wake = null
                        wakeListening = false
                        overlay?.setWakeListening(false)
                        startWakeWord()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    // ---------------- Music ----------------

    private var audioWaitToken = 0

    /**
     * The Bluetooth link comes up 1–3 s before the car's audio channel does. Playing
     * before then sends music out of the phone speaker, so wait (up to 15 s) for it.
     */
    private fun whenCarAudioReady(action: () -> Unit) {
        val token = ++audioWaitToken
        // Without the Bluetooth permission we can't ask; just give the car 3 s.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            handler.postDelayed({
                if (token == audioWaitToken && isRunning && !disconnectPending) action()
            }, 3000)
            return
        }
        statusText = "Waiting for car audio…"
        updateNotification()
        fun attempt(n: Int) {
            CarConnection.check(this, musicReady = true) { ready ->
                if (token != audioWaitToken || !isRunning || disconnectPending) return@check
                when {
                    ready -> {
                        statusText = "Connected to ${Prefs.carName(this) ?: "your car"}"
                        action()
                        updateNotification()
                    }
                    n < 15 -> handler.postDelayed({ attempt(n + 1) }, 1000)
                    else -> {
                        Log.w(TAG, "Car audio never became ready; not auto-playing")
                        statusText = "Car audio not ready. Tap Play to start music."
                        updateNotification()
                    }
                }
            }
        }
        attempt(0)
    }

    /**
     * Carries on from where the music last stopped (same folder, song and second).
     * Falls back to the default folder if that song or folder is gone.
     */
    private fun resumeOrStartDefault() {
        if (!mayPlay()) return
        invalidatePlaybackRequests()
        val request = playbackGeneration
        // Reading the music library can take a moment with many songs: do it off the main thread.
        onLibrary {
            val r = Prefs.loadResume(this)
            val folder = r?.let { MusicLibrary.folder(this, it.folder) }
            val index = if (r == null) -1 else folder?.tracks?.indexOfFirst { it.uri.toString() == r.trackUri } ?: -1
            val fallback = if (folder == null || index < 0) MusicLibrary.folder(this, MusicLibrary.DEFAULT_FOLDER) else null
            handler.post {
                if (!mayApplyPlayback(request)) return@post
                if (folder != null && index >= 0) {
                    Log.i(TAG, "Resuming ${folder.name}, track ${index + 1} at ${r.positionMs / 1000}s")
                    playFolder(folder, index, r.positionMs)
                } else {
                    playDefault(fallback)
                }
            }
        }
    }

    /** Single background thread for music library lookups; results come back on the main thread. */
    private val library = java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun onLibrary(work: () -> Unit) {
        if (library.isShutdown) return
        try {
            library.execute {
                try { work() } catch (e: Exception) { Log.e(TAG, "Music library: ${e.message}", e) }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            // James is shutting down
        }
    }

    /** Remembers the current folder, song and position. */
    private fun saveResumePoint() {
        val p = player ?: return
        val folder = currentFolder ?: return
        val uri = p.currentMediaItem?.localConfiguration?.uri ?: return
        Prefs.saveResume(this, folder, uri.toString(), p.currentPosition)
    }

    private fun startDefaultMusic() {
        if (!mayPlay()) return
        invalidatePlaybackRequests()
        val request = playbackGeneration
        onLibrary {
            val folder = MusicLibrary.folder(this, MusicLibrary.DEFAULT_FOLDER)
            handler.post { if (mayApplyPlayback(request)) playDefault(folder) }
        }
    }

    /** Main thread: plays the default folder, or explains that it's empty. */
    private fun playDefault(folder: MusicFolder?) {
        if (folder == null || folder.tracks.isEmpty()) {
            Log.w(TAG, "No songs in Music/${MusicLibrary.DEFAULT_FOLDER}")
            statusText = "No songs in Music/${MusicLibrary.DEFAULT_FOLDER}"
            Toast.makeText(applicationContext, "Azarah: no songs in Music/${MusicLibrary.DEFAULT_FOLDER}", Toast.LENGTH_LONG).show()
            updateNotification()
            return
        }
        playFolder(folder)
    }

    /** Looks the folder up in the background, then plays it. [then] gets false if it has no songs. */
    private fun playFolderNamed(name: String, then: ((Boolean) -> Unit)? = null) {
        if (!mayPlay()) return
        invalidatePlaybackRequests()
        val request = playbackGeneration
        onLibrary {
            val folder = MusicLibrary.folder(this, name)
            handler.post {
                if (!mayApplyPlayback(request)) return@post
                if (folder == null || folder.tracks.isEmpty()) {
                    Toast.makeText(applicationContext, "Azarah: no songs in Music/$name", Toast.LENGTH_LONG).show()
                    speaker?.say("Sorry, I couldn't find songs in ${OverlayController.spoken(name)}")
                    then?.invoke(false)
                } else {
                    playFolder(folder)
                    then?.invoke(true)
                }
            }
        }
    }

    private fun togglePlay() {
        if (!mayPlay()) return
        val p = player
        when {
            p == null || p.mediaItemCount == 0 -> resumeOrStartDefault()
            p.playWhenReady -> pausePlayback()
            else -> {
                invalidatePlaybackRequests()
                if (p.playbackState == Player.STATE_IDLE) p.prepare()   // recover after an error
                p.play()
            }
        }
    }

    // ---------------- Navigation ----------------

    /**
     * Opens Google Maps in turn-by-turn mode. Must be called while the overlay is on screen:
     * Android lets an app with "display over other apps" open another app only then.
     */
    private fun navigateTo(address: String): Boolean {
        val q = Uri.encode(address.trim())
        val attempts = listOf(
            // 1. Google Maps, straight into driving navigation
            Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$q&mode=d"))
                .setPackage("com.google.android.apps.maps"),
            // 2. Any maps app
            Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$q")),
            // 3. Google Maps in the browser
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$q&travelmode=driving"))
        )
        for (intent in attempts) {
            try {
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                Prefs.addRecent(this, address)
                Log.i(TAG, "Navigation started: $address via ${intent.data?.scheme}")
                speaker?.say("Starting trip to ${address.trim()}")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't open ${intent.data?.scheme}: ${e.message}")
            }
        }
        speaker?.say("Sorry, I couldn't open the map")
        return false
    }

    /**
     * Speech recognition needs this service to be allowed to use the microphone.
     * Adds the microphone type to the running foreground service if it isn't there yet.
     */
    private fun ensureMicType() {
        if (micTypeActive || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(),
                currentFgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
            currentFgsType = currentFgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            micTypeActive = true
            Log.i(TAG, "Microphone service type added")
        } catch (e: Exception) {
            // James keeps running with its current types; listening may still work on some phones.
            Log.w(TAG, "Couldn't add microphone type: ${e.message}")
        }
    }

    // ---------------- Wake word ----------------

    /**
     * Android 11+ blocks the microphone for apps started in the background (as James is, when
     * the car connects): the wake word then hears only silence. Once James is on screen, Android
     * allows it, so re-start the foreground service with the microphone type and restart the
     * listener. Done once per drive.
     */
    private fun refreshMicAccess() {
        if (micRefreshed || !isRunning || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
            currentFgsType = type
            micTypeActive = true
            micRefreshed = true
            Log.i(TAG, "Microphone unlocked while Azarah is on screen; restarting the wake word")
            // Restart the listener so it records with real mic access (unless James is mid-command).
            if (!micBusyNow) {
                wake?.pause()
                wake?.resume(300)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Microphone still not allowed: ${e.message}")
        }
    }

    private fun startWakeWord() {
        if (wake == null) {
            wake = WakeWord(
                this,
                onWake = { command -> onWakeWord(command) },
                onStatus = { listening, status ->
                    wakeListening = listening
                    overlay?.setWakeListening(listening)
                    Log.i(TAG, "Wake word: $status")
                }
            )
        }
        ensureMicType()   // the mic must be allowed for this service before listening in the background
        if (!micBusyNow) wake?.start()
    }

    /** [command]: said in the same breath ("Azarah, play the music"), or null to ask and listen. */
    private fun onWakeWord(command: String?) {
        Log.i(TAG, "Wake word heard" + (command?.let { ", command: $it" } ?: ""))
        val o = overlay ?: OverlayController(this, overlayActions).also { overlay = it }
        if (!o.canDraw()) {
            speaker?.say("I need permission to show over other apps. Please open Azarah setup.")
            return
        }
        o.voiceCommand(command)
    }

    // ---------------- Overlay ----------------

    private fun showOverlay() {
        val o = overlay ?: OverlayController(this, overlayActions).also { overlay = it }
        if (!o.canDraw()) {
            Log.w(TAG, "No 'display over other apps' permission yet; will show the bubble once it's allowed")
            handler.removeCallbacks(overlayPermissionCheck)
            handler.postDelayed(overlayPermissionCheck, 2000)
            return
        }
        o.expand(OverlayController.Page.HOME)
    }

    /** Every 2 s while running without the overlay permission: show the bubble as soon as it's granted. */
    private val overlayPermissionCheck = object : Runnable {
        override fun run() {
            if (!isRunning) return
            val o = overlay ?: return
            if (o.canDraw()) {
                Log.i(TAG, "Overlay permission granted; showing the bubble")
                o.ensureVisible()
            } else {
                handler.postDelayed(this, 2000)
            }
        }
    }

    private fun playFolder(folder: MusicFolder, startIndex: Int = 0, startPositionMs: Long = 0) {
        if (!mayPlay() || folder.tracks.isEmpty()) return
        val p = ensurePlayer()
        val items = folder.tracks.map { t ->
            MediaItem.Builder()
                .setUri(t.uri)
                .setMediaMetadata(
                    MediaMetadata.Builder().setTitle(t.title).setAlbumTitle(folder.name).build()
                )
                .build()
        }
        currentFolder = folder.name
        consecutiveErrors = 0
        p.shuffleModeEnabled = folder.name == MusicLibrary.ALL_SONGS
        p.setMediaItems(items, startIndex.coerceIn(0, items.size - 1), startPositionMs)
        p.prepare()
        p.play()
        Log.i(TAG, "Playing ${folder.name} (${items.size} tracks)")
    }

    @androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val p = ExoPlayer.Builder(this)
            // true = James takes audio focus, so other apps pause and Maps can duck the music.
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)   // pause if Bluetooth drops, instead of blasting the phone speaker
            .build()
        p.repeatMode = Player.REPEAT_MODE_ALL
        p.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // MediaSession sends steering-wheel/headset Pause straight to the player.
                // Buffering and transient audio-focus changes must not cancel a requested playlist.
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) {
                    invalidatePlaybackRequests()
                }
                updateNotification()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) consecutiveErrors = 0 else saveResumePoint()
                updateNotification()
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                saveResumePoint()   // new song: remember it straight away
                updateNotification()
            }
            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "Can't play this track, skipping: ${error.message}")
                consecutiveErrors++
                if (consecutiveErrors >= p.mediaItemCount) {
                    // Every song failed: stop instead of skipping forever.
                    Log.e(TAG, "No playable songs in $currentFolder")
                    statusText = "Couldn't play any song in $currentFolder"
                    updateNotification()
                    return
                }
                if (p.mediaItemCount > 1) {
                    p.seekToNextMediaItem()
                    p.prepare()
                    p.play()
                }
            }
        })
        // The media session is what lets the car's steering-wheel and earbud buttons control James.
        val controls = object : ForwardingPlayer(p) {
            // An explicit controller Pause must cancel pending work even if already paused.
            override fun pause() = pausePlayback()
            override fun play() = overlayActions.play()
            override fun setPlayWhenReady(playWhenReady: Boolean) {
                if (playWhenReady) overlayActions.play() else pausePlayback()
            }
        }
        session = MediaSession.Builder(this, controls).build()
        player = p
        return p
    }

    private fun currentTitle(): String? =
        player?.currentMediaItem?.mediaMetadata?.title?.toString()

    // ---------------- Notification ----------------

    private var currentFgsType = 0
    private var micTypeActive = false

    /**
     * Starts (or refreshes) the foreground service. Tries the most capable set of types first
     * and falls back, so James always stays alive:
     *   Bluetooth + music + microphone  ->  Bluetooth + music  ->  Bluetooth only
     */
    private fun goForeground(): Boolean {
        createChannel()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return try {
                startForeground(NOTIFICATION_ID, buildNotification())
                true
            } catch (e: Exception) {
                Log.e(TAG, "startForeground failed: ${e.message}", e)
                isRunning = false
                stopSelf()
                false
            }
        }
        val bt = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        val media = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        val micAllowed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val options = buildList {
            if (micAllowed) add(bt or media or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            add(bt or media)
            add(bt)
        }
        for (type in options) {
            try {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
                currentFgsType = type
                micTypeActive = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    (type and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) != 0
                return true
            } catch (e: Exception) {
                Log.w(TAG, "startForeground(type $type) refused: ${e.message}")
            }
        }
        Log.e(TAG, "Couldn't start the foreground service at all")
        isRunning = false
        stopSelf()
        return false
    }

    private fun updateNotification() {
        val title = currentTitle()
        nowPlaying = if (title != null) "$title — $currentFolder" else null
        overlay?.onPlaybackChanged()
        if (!isRunning && !disconnectPending) return
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
        } catch (e: SecurityException) {
            Log.w(TAG, "Can't update notification: ${e.message}")
        }
    }

    private fun serviceIntent(action: String, code: Int) = PendingIntent.getService(
        this, code,
        Intent(this, JamesService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun buildNotification(): Notification {
        // Tapping the notification opens James's overlay.
        val openOverlay = serviceIntent(ACTION_SHOW_OVERLAY, 5)
        val title = currentTitle()
        val playing = player?.playWhenReady == true
        val text = when {
            disconnectPending -> statusText
            title != null && consecutiveErrors == 0 -> "♪ $title  ·  $currentFolder"
            else -> statusText
        }

        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_james)
            .setContentTitle("Azarah is active")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openOverlay)

        if (!disconnectPending) {
            if (player != null) b.addAction(0, "Previous", serviceIntent(ACTION_PREV, 2))
            b.addAction(0, if (playing) "Pause" else "Play", serviceIntent(ACTION_TOGGLE, 3))
            if (player != null) b.addAction(0, "Next", serviceIntent(ACTION_NEXT, 4))
        }
        b.addAction(0, "Stop", serviceIntent(ACTION_STOP, 1))
        return b.build()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Azarah active", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    // ---------------- Shutdown ----------------

    private fun releaseUi() {
        wake?.release()
        wake = null
        wakeListening = false
        overlay?.release()
        overlay = null
        speaker?.shutdown()
        speaker = null
    }

    private fun releasePlayer() {
        saveResumePoint()
        session?.release()
        session = null
        player?.release()
        player = null
        currentFolder = null
        nowPlaying = null
    }

    private fun shutDown() {
        destroyed = true
        invalidatePlaybackRequests()
        handler.removeCallbacksAndMessages(null)
        isRunning = false
        disconnectPending = false
        releasePlayer()
        releaseUi()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        destroyed = true
        invalidatePlaybackRequests()
        library.shutdownNow()
        handler.removeCallbacksAndMessages(null)
        isRunning = false
        releasePlayer()
        releaseUi()
        Log.i(TAG, "JamesService destroyed")
        super.onDestroy()
    }
}

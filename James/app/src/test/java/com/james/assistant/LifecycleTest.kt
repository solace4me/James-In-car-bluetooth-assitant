package com.james.assistant

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import com.james.assistant.overlay.OverlayController
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowSpeechRecognizer
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
@Suppress("DEPRECATION") // Robolectric's compatibility helpers intentionally exercise older Android APIs.
class LifecycleTest {
    private lateinit var app: Application

    @Before fun prepare() {
        app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("james", 0).edit().clear().commit()
        Parking.cancelPending()
        Parking.setEnabled(app, false)
    }

    @After fun cleanup() { Parking.cancelPending() }

    @Test fun revokedBluetoothPermissionReturnsDisconnected() {
        Prefs.saveCar(app, "00:11:22:33:44:55", "Car")
        shadowOf(BluetoothAdapter.getDefaultAdapter()).setEnabled(true)
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        var result: Boolean? = null
        CarConnection.check(app) { result = it }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(false, result)
    }

    @Test fun disconnectDiscardsPendingPlaylistResult() {
        withPendingPlaylist { service, queue, result ->
            service.onStartCommand(Intent(app, JamesService::class.java).setAction(JamesService.ACTION_DISCONNECTED), 0, 2)
            queue.runPending()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, result[0])
        }
    }

    @Test fun pauseDiscardsPendingPlaylistResult() {
        withPendingPlaylist { service, queue, result ->
            val field = JamesService::class.java.getDeclaredField("overlayActions").apply { isAccessible = true }
            (field.get(service) as OverlayController.Actions).pause()
            queue.runPending()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, result[0])
        }
    }

    @Test fun onlyLatestPlaylistRequestCanComplete() {
        withPendingPlaylist { service, queue, result ->
            val request = JamesService::class.java.getDeclaredMethod("playFolderNamed", String::class.java, kotlin.jvm.functions.Function1::class.java)
                .apply { isAccessible = true }
            var newer = 0
            val callback: (Boolean) -> Unit = { newer++ }
            request.invoke(service, "Newer missing playlist", callback)
            queue.runPending()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, result[0])
            assertEquals(1, newer)
        }
    }

    private fun withPendingPlaylist(check: (JamesService, ManualExecutor, IntArray) -> Unit) {
        val controller = Robolectric.buildService(JamesService::class.java).create()
        val service = controller.get()
        val queue = ManualExecutor()
        JamesService::class.java.getDeclaredField("library").apply { isAccessible = true }.set(service, queue)
        JamesService::class.java.getDeclaredField("isRunning").apply { isAccessible = true }.setBoolean(null, true)
        val result = intArrayOf(0)
        val request = JamesService::class.java.getDeclaredMethod("playFolderNamed", String::class.java, kotlin.jvm.functions.Function1::class.java)
            .apply { isAccessible = true }
        val callback: (Boolean) -> Unit = { result[0]++ }
        request.invoke(service, "Missing", callback)
        try { check(service, queue, result) } finally { controller.destroy() }
    }

    @Test fun clearingParkingInvalidatesAnInflightCapture() = parkingCaptureCancelled { Parking.clear(app) }
    @Test fun disablingParkingInvalidatesAnInflightCapture() = parkingCaptureCancelled { Parking.setEnabled(app, false) }

    private fun parkingCaptureCancelled(cancel: () -> Unit) {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        Parking.setEnabled(app, true)
        val manager = app.getSystemService(LocationManager::class.java)
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        val fix = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = 6.5; longitude = 3.3; accuracy = 10f
            time = System.currentTimeMillis(); elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        shadowOf(manager).setLastKnownLocation(LocationManager.GPS_PROVIDER, fix)
        Parking.capture(app)
        cancel()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(9))
        assertNull(Parking.last(app))
    }

    @Test fun parkingYesterdayUsesCalendarDate() {
        val previousDate = LocalDate.now().minusDays(1).atTime(23, 59).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val text = Parking.timeLabel(app, Parking.Spot(1.0, 1.0, 1f, previousDate))
        assertTrue(text, text.startsWith("yesterday"))
    }

    @Test fun cancelTripRejectsLateGoResult() {
        ShadowSettings.setCanDrawOverlays(true)
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val actions = RecordingActions()
        val overlay = OverlayController(app, actions)
        try {
            overlay.expand(OverlayController.Page.MAP)
            OverlayController::class.java.getDeclaredMethod("confirm", String::class.java, String::class.java)
                .apply { isAccessible = true }.invoke(overlay, "123 Example Road", "Office")
            overlay.voiceCommand()
            OverlayController::class.java.getDeclaredMethod("cancelTrip").apply { isAccessible = true }.invoke(overlay)
            val callback = OverlayController::class.java.getDeclaredField("voiceCallback").apply { isAccessible = true }.get(overlay) as VoiceInput.Callback
            callback.onResult(listOf("go"))
            assertTrue(actions.destinations.isEmpty())
            val address = OverlayController::class.java.getDeclaredField("pendingAddress").apply { isAccessible = true }.get(overlay)
            assertNull(address)
        } finally { overlay.release() }
    }

    @Test fun misheardWordsDuringCountdownResumeTheTrip() {
        ShadowSettings.setCanDrawOverlays(true)
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val actions = RecordingActions()
        val overlay = OverlayController(app, actions)
        try {
            overlay.expand(OverlayController.Page.MAP)
            shadowOf(Looper.getMainLooper()).idle()
            OverlayController::class.java.getDeclaredMethod("confirm", String::class.java, String::class.java)
                .apply { isAccessible = true }.invoke(overlay, "123 Example Road", "Office")
            overlay.voiceCommand()   // "Hey James" mid-countdown: the countdown pauses
            val callback = OverlayController::class.java.getDeclaredField("voiceCallback").apply { isAccessible = true }.get(overlay) as VoiceInput.Callback
            callback.onResult(listOf("banana"))   // not Go, Cancel or a command
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
            assertEquals(listOf("123 Example Road"), actions.destinations)
        } finally { overlay.release() }
    }

    @Test fun notCatchingACommandListensAgainWithoutTheWakeWord() {
        ShadowSettings.setCanDrawOverlays(true)
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val actions = RecordingActions()
        val overlay = OverlayController(app, actions)
        val mode = OverlayController::class.java.getDeclaredField("voiceMode").apply { isAccessible = true }
        try {
            overlay.voiceCommand()
            val callback = OverlayController::class.java.getDeclaredField("voiceCallback").apply { isAccessible = true }.get(overlay) as VoiceInput.Callback
            repeat(2) {
                callback.onError("no-match")
                assertEquals("still listening after retry ${it + 1}", "COMMAND", mode.get(overlay)?.toString())
                assertTrue(actions.spoken.last(), actions.spoken.last().startsWith("Sorry, I didn't catch that"))
            }
            callback.onError("no-match")   // third miss: give up, back to the wake word
            assertEquals("NONE", mode.get(overlay)?.toString())
            assertTrue(actions.spoken.last(), actions.spoken.last().contains(WakePhrase.PHRASE))
        } finally { overlay.release() }
    }

    @Test fun wakeWordWithACommandActsWithoutAskingFirst() {
        ShadowSettings.setCanDrawOverlays(true)
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val actions = RecordingActions()
        val overlay = OverlayController(app, actions)
        try {
            overlay.voiceCommand("next song")   // "Azarah, next song"
            assertEquals(1, actions.nextCalls)
            assertTrue("no 'I'm listening' prompt", actions.spoken.isEmpty())
            overlay.voiceCommand("map")         // "Azarah, take me to …": opens the map and asks
            assertTrue(overlay.isExpanded)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("Where are we going?", actions.spoken.last())
        } finally { overlay.release() }
    }

    @Test fun oldSpeechErrorCannotEndANewerRecognitionSession() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val resolve = ResolveInfo().apply {
            serviceInfo = ServiceInfo().apply { packageName = "test.speech"; name = "Recognition" }
        }
        shadowOf(app.packageManager).addResolveInfoForIntent(Intent(RecognitionService.SERVICE_INTERFACE), resolve)
        Settings.Secure.putString(app.contentResolver, "voice_recognition_service", "test.speech/Recognition")
        var errors = 0
        val voice = VoiceInput(app, object : VoiceInput.Callback {
            override fun onListening() {}
            override fun onPartial(text: String) {}
            override fun onResult(alternatives: List<String>) {}
            override fun onError(reason: String) { errors++ }
        })
        try {
            voice.start()
            shadowOf(Looper.getMainLooper()).idle()
            val previous = shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
            voice.start()
            shadowOf(Looper.getMainLooper()).idle()
            previous.triggerOnError(SpeechRecognizer.ERROR_CLIENT)
            assertEquals(0, errors)
            assertFalse(shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer()).isDestroyed)
        } finally { voice.cancel() }
    }

    @Test fun queuedWakeErrorCannotRestartWhilePaused() {
        val statuses = mutableListOf<String>()
        val wake = WakeWord(app, {}, { _, status -> statuses += status })
        WakeWord::class.java.getDeclaredField("running").apply { isAccessible = true }.setBoolean(wake, true)
        WakeWord::class.java.getDeclaredField("wanted").apply { isAccessible = true }.setBoolean(wake, true)
        val listener = WakeWord::class.java.getDeclaredField("listener").apply { isAccessible = true }.get(wake) as org.vosk.android.RecognitionListener
        listener.onError(IllegalStateException("old recording ended"))
        wake.pause()
        statuses.clear()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertTrue(statuses.toString(), statuses.isEmpty())
        wake.release()
    }

    @Test fun notificationPauseAlsoDiscardsTheOverlayLookup() {
        withPendingPlaylist { service, _, _ ->
            val overlay = OverlayController(app, RecordingActions())
            val queue = ManualExecutor()
            OverlayController::class.java.getDeclaredField("io").apply { isAccessible = true }.set(overlay, queue)
            JamesService::class.java.getDeclaredField("overlay").apply { isAccessible = true }.set(service, overlay)
            var completed = 0
            val callback: (String?) -> Unit = { completed++ }
            OverlayController::class.java.getDeclaredMethod("findFolderAsync", String::class.java, kotlin.jvm.functions.Function1::class.java)
                .apply { isAccessible = true }.invoke(overlay, "Missing", callback)
            JamesService::class.java.getDeclaredMethod("pausePlayback").apply { isAccessible = true }.invoke(service)
            queue.runPending()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, completed)
        }
    }

    @Test fun mediaSessionPlayerPauseDiscardsPendingPlaylistResult() {
        withPendingPlaylist { service, queue, result ->
            val player = JamesService::class.java.getDeclaredMethod("ensurePlayer").apply { isAccessible = true }.invoke(service) as androidx.media3.common.Player
            player.playWhenReady = true
            player.pause()
            queue.runPending()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, result[0])
        }
    }

    @Test fun redundantMediaSessionPauseStillCancelsPendingPlayback() {
        withPendingPlaylist { service, queue, result ->
            JamesService::class.java.getDeclaredMethod("ensurePlayer").apply { isAccessible = true }.invoke(service)
            val session = JamesService::class.java.getDeclaredField("session").apply { isAccessible = true }.get(service) as androidx.media3.session.MediaSession
            assertFalse(session.player.playWhenReady)
            session.player.pause()
            queue.runPending()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, result[0])
        }
    }

    @Test fun togglePausesAPlayerThatHasNotStartedRenderingYet() {
        withPendingPlaylist { service, _, _ ->
            val player = JamesService::class.java.getDeclaredMethod("ensurePlayer").apply { isAccessible = true }.invoke(service) as androidx.media3.common.Player
            player.setMediaItem(androidx.media3.common.MediaItem.fromUri("file:///missing.mp3"))
            player.playWhenReady = true
            assertFalse(player.isPlaying)
            JamesService::class.java.getDeclaredMethod("togglePlay").apply { isAccessible = true }.invoke(service)
            assertFalse(player.playWhenReady)
        }
    }

    @Test fun notificationShowsPauseWhilePlaybackIsRequestedButNotRendering() {
        withPendingPlaylist { service, _, _ ->
            val player = JamesService::class.java.getDeclaredMethod("ensurePlayer").apply { isAccessible = true }.invoke(service) as androidx.media3.common.Player
            player.setMediaItem(androidx.media3.common.MediaItem.fromUri("file:///missing.mp3"))
            player.playWhenReady = true
            shadowOf(Looper.getMainLooper()).idle()
            val notification = shadowOf(app.getSystemService(android.app.NotificationManager::class.java)).getNotification(1)
            assertTrue(notification.actions.any { it.title.toString() == "Pause" })
        }
    }

    @Test fun bubbleCanBeOpenedByAnAccessibilityClick() {
        ShadowSettings.setCanDrawOverlays(true)
        val overlay = OverlayController(app, RecordingActions())
        try {
            val bubble = OverlayController::class.java.getDeclaredMethod("buildBubble").apply { isAccessible = true }.invoke(overlay) as android.view.View
            assertTrue(bubble.performClick())
            assertNotNull(OverlayController::class.java.getDeclaredField("sheet").apply { isAccessible = true }.get(overlay))
        } finally { overlay.release() }
    }

    @Test fun supersededMapPromptCannotStartRecognition() {
        ShadowSettings.setCanDrawOverlays(true)
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val actions = RecordingActions()
        val overlay = OverlayController(app, actions)
        try {
            overlay.expand(OverlayController.Page.MAP)
            shadowOf(Looper.getMainLooper()).idle()
            val previousPrompt = actions.completions.last()
            OverlayController::class.java.getDeclaredMethod("listen", String::class.java)
                .apply { isAccessible = true }.invoke(overlay, "Say that again")
            previousPrompt()
            val mode = OverlayController::class.java.getDeclaredField("voiceMode").apply { isAccessible = true }.get(overlay)
            assertEquals("MAP", mode?.toString())
        } finally { overlay.release() }
    }

    @Test fun wakeReloadDefersWhileCommandOwnsMicrophone() {
        withPendingPlaylist { service, _, _ ->
            JamesService::class.java.getDeclaredField("micBusyNow").apply { isAccessible = true }.setBoolean(service, true)
            service.onStartCommand(Intent(app, JamesService::class.java).setAction(JamesService.ACTION_WAKE_RELOAD), 0, 3)
            val wake = JamesService::class.java.getDeclaredField("wake").apply { isAccessible = true }.get(service)
            val wanted = WakeWord::class.java.getDeclaredField("wanted").apply { isAccessible = true }.getBoolean(wake)
            assertFalse(wanted)
        }
    }

    private class ManualExecutor : AbstractExecutorService() {
        private val tasks = mutableListOf<Runnable>()
        private var stopped = false
        override fun execute(command: Runnable) { tasks += command }
        fun runPending() { val copy = tasks.toList(); tasks.clear(); copy.forEach { it.run() } }
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> { stopped = true; return tasks.toMutableList().also { tasks.clear() } }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped && tasks.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
    }

    private class RecordingActions : OverlayController.Actions {
        val destinations = mutableListOf<String>()
        val completions = mutableListOf<() -> Unit>()
        val spoken = mutableListOf<String>()
        override fun playFolder(name: String) {}
        override fun shuffleAll() {}
        override fun currentFolder(): String? = null
        override fun nowPlaying(): OverlayController.NowPlaying? = null
        override fun previous() {}
        override fun togglePlay() {}
        var nextCalls = 0
        override fun next() { nextCalls++ }
        override fun statusLine() = "Ready"
        override fun say(text: String, onDone: (() -> Unit)?) { spoken += text; if (onDone != null) completions += onDone }
        override fun navigateTo(address: String): Boolean { destinations += address; return true }
        override fun listeningStarted() {}
        override fun micBusy(busy: Boolean) {}
        override fun uiVisible() {}
        override fun play() {}
        override fun pause() {}
    }
}

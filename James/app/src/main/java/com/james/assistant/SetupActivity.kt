package com.james.assistant

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.concurrent.Executors

/**
 * One-time setup, done parked (never while driving):
 * a status card (running / car / now playing, Start and Stop), then a 4-step checklist,
 * then what music James found.
 */
class SetupActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val denied = result.filterValues { !it }.keys
            // Android stops showing the dialog after two refusals; send them to app settings instead.
            if (denied.isNotEmpty() && denied.none { shouldShowRequestPermissionRationale(it) }) {
                toast("Please allow the missing permissions in Settings → Permissions")
                openAppSettings()
            }
            if (result.values.any { it }) JamesService.send(this, JamesService.ACTION_WAKE_RELOAD, "permissions changed")
            refresh()
        }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", packageName, null))
        )
    }

    private val chooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { onCarChosen(it) }

    // ---------- Views updated by refresh() ----------

    private lateinit var statusDot: View
    private lateinit var statusTitle: TextView
    private lateinit var statusCar: TextView
    private lateinit var statusSong: TextView
    private lateinit var musicDefault: TextView
    private lateinit var musicPlaylists: TextView

    /** One row of the checklist. */
    private class Step(val badge: LinearLayout, val badgeIcon: ImageView, val badgeNumber: TextView, val detail: TextView)
    private val steps = mutableListOf<Step>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG
        setContentView(buildUi())
    }

    // Keep the screen live while it's open.
    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            uiHandler.postDelayed(this, 1000)
        }
    }

    override fun onResume() {
        super.onResume()
        uiHandler.post(ticker)
    }

    override fun onPause() {
        uiHandler.removeCallbacks(ticker)
        super.onPause()
    }

    // ================= Layout =================

    private fun buildUi(): View {
        val col = Ui.column(this)
        val side = dp(20)

        // Header
        val header = Ui.row(this)
        header.addView(TextView(this).apply {
            text = "J"
            setTextColor(Color.WHITE)
            textSize = 24f
            typeface = Ui.BOLD
            gravity = Gravity.CENTER
            background = Ui.oval(this@SetupActivity, 0xFF1E1E22.toInt(), Ui.GREEN, 2)
        }, LinearLayout.LayoutParams(dp(52), dp(52)))
        val names = Ui.column(this).apply { setPadding(dp(14), 0, 0, 0) }
        names.addView(Ui.text(this, "James", 28f, bold = true))
        names.addView(Ui.text(this, "Your in-car assistant", 16f, Ui.TEXT_2).apply { setPadding(0, dp(4), 0, 0) })
        header.addView(names)
        col.addView(header, matchWrap(bottom = 24))

        // Status card
        col.addView(statusCard(), matchWrap(bottom = 32))

        // Checklist
        col.addView(Ui.label(this, "Setup · do this once, parked"), matchWrap(bottom = 12))
        val list = Ui.column(this).apply { background = Ui.card(this@SetupActivity, 20) }
        list.addView(stepRow(1, "Permissions", { requestPermissions() }))
        list.addView(divider())
        list.addView(stepRow(2, "Your car", { chooseCar() }))
        list.addView(divider())
        list.addView(stepRow(3, "Run in background", { openBatterySettings() }))
        list.addView(divider())
        list.addView(stepRow(4, "Show over other apps", { openOverlaySettings() }))
        col.addView(list, matchWrap(bottom = 32))

        // Wake word
        col.addView(Ui.label(this, "Wake word · \"Hey James\""), matchWrap(bottom = 12))
        val wakeCard = Ui.column(this).apply { background = Ui.card(this@SetupActivity, 20) }
        keyRow = placeRow(R.drawable.ic_mic, "Voice model (${VoiceModel.SIZE_MB} MB, once)") { downloadModel() }
        wakeCard.addView(keyRow)
        wakeCard.addView(divider())
        wakeInfo = Ui.text(this, "", 15f, Ui.TEXT_2).apply {
            setPadding(dp(16), dp(14), dp(16), dp(16))
            setLineSpacing(0f, 1.25f)
        }
        wakeCard.addView(wakeInfo)
        col.addView(wakeCard, matchWrap(bottom = 32))

        // Places
        col.addView(Ui.label(this, "Places · say \"home\" or \"office\""), matchWrap(bottom = 12))
        val places = Ui.column(this).apply { background = Ui.card(this@SetupActivity, 20) }
        homeRow = placeRow(R.drawable.ic_home, "Home") { editPlace("Home", Prefs.home(this)) { Prefs.setHome(this, it) } }
        places.addView(homeRow)
        places.addView(divider())
        officeRow = placeRow(R.drawable.ic_work, "Office") { editPlace("Office", Prefs.office(this)) { Prefs.setOffice(this, it) } }
        places.addView(officeRow)
        col.addView(places, matchWrap(bottom = 32))

        // Music
        col.addView(Ui.label(this, "Music"), matchWrap(bottom = 12))
        val music = Ui.column(this).apply {
            background = Ui.card(this@SetupActivity, 20)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        musicDefault = Ui.text(this, "Reading music…", 18f, bold = true)
        musicPlaylists = Ui.text(this, "", 16f, Ui.TEXT_2).apply {
            setPadding(0, dp(8), 0, 0)
            setLineSpacing(0f, 1.25f)
        }
        music.addView(musicDefault)
        music.addView(musicPlaylists)
        col.addView(music, matchWrap(bottom = 16))
        col.addView(Ui.text(this, "Songs go in Music/James_Default on your phone. Each other folder in Music becomes a playlist.",
            15f, Ui.TEXT_3).apply { setLineSpacing(0f, 1.25f) }, matchWrap())

        // Android 15+ draws apps behind the status and navigation bars; pad around them.
        ViewCompat.setOnApplyWindowInsetsListener(col) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(side, bars.top + dp(24), side, bars.bottom + dp(24))
            insets
        }
        col.setPadding(side, dp(24), side, dp(24))
        return ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            addView(col)
        }
    }

    private fun statusCard(): View {
        val card = Ui.column(this).apply {
            background = Ui.card(this@SetupActivity, 24)
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        val top = Ui.row(this)
        statusDot = View(this).apply { background = Ui.oval(this@SetupActivity, Ui.GREY_DOT) }
        top.addView(statusDot, LinearLayout.LayoutParams(dp(12), dp(12)))
        statusTitle = Ui.text(this, "James is off", 22f, bold = true).apply { setPadding(dp(12), 0, 0, 0) }
        top.addView(statusTitle)
        card.addView(top)

        statusCar = Ui.text(this, "", 16f, Ui.TEXT_2, oneLine = true).apply { setPadding(0, dp(12), 0, 0) }
        statusSong = Ui.text(this, "", 16f, Ui.TEXT_2, oneLine = true).apply { setPadding(0, dp(6), 0, 0) }
        card.addView(statusCar)
        card.addView(statusSong)

        val buttons = Ui.row(this)
        buttons.addView(pillButton("Start James", filled = true) {
            CarConnection.check(this) { connected ->
                if (connected) {
                    JamesService.send(this, JamesService.ACTION_CONNECTED, "start button")
                } else {
                    toast("Your car isn't connected, so James won't start. Turn its Bluetooth on first.")
                }
            }
        }, LinearLayout.LayoutParams(0, dp(56), 1f).apply { marginEnd = dp(6) })
        buttons.addView(pillButton("Stop", filled = false) {
            JamesService.send(this, JamesService.ACTION_STOP, "stop button")
        }, LinearLayout.LayoutParams(0, dp(56), 1f).apply { marginStart = dp(6) })
        card.addView(buttons, matchWrap(top = 20))
        return card
    }

    private fun pillButton(label: String, filled: Boolean, onClick: () -> Unit): View =
        LinearLayout(this).apply {
            gravity = Gravity.CENTER
            background = Ui.pressable(
                if (filled) Ui.rounded(this@SetupActivity, Ui.GREEN, 28)
                else Ui.rounded(this@SetupActivity, Ui.SURFACE_HI, 28, Ui.HAIRLINE, 1)
            )
            isClickable = true
            isFocusable = true
            contentDescription = label
            setOnClickListener { onClick() }
            addView(Ui.text(this@SetupActivity, label, 18f, if (filled) Ui.BG else Ui.TEXT, bold = true))
        }

    private fun stepRow(number: Int, title: String, onClick: () -> Unit): View {
        val row = Ui.row(this).apply {
            setPadding(dp(16), dp(16), dp(12), dp(16))
            background = Ui.pressable(Ui.rounded(this@SetupActivity, Color.TRANSPARENT, 20))
            isClickable = true
            isFocusable = true
            minimumHeight = dp(76)
            setOnClickListener { onClick() }
        }
        val badge = LinearLayout(this).apply { gravity = Gravity.CENTER }
        val badgeIcon = Ui.icon(this, R.drawable.ic_check, Ui.BG)
        val badgeNumber = Ui.text(this, number.toString(), 16f, Ui.TEXT, bold = true)
        badge.addView(badgeIcon, LinearLayout.LayoutParams(dp(20), dp(20)))
        badge.addView(badgeNumber)
        row.addView(badge, LinearLayout.LayoutParams(dp(36), dp(36)))

        val texts = Ui.column(this).apply { setPadding(dp(14), 0, dp(8), 0) }
        texts.addView(Ui.text(this, title, 18f, bold = true))
        val detail = Ui.text(this, "", 15f, Ui.TEXT_2).apply { setPadding(0, dp(4), 0, 0) }
        texts.addView(detail)
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Ui.icon(this, R.drawable.ic_chevron_right, Ui.TEXT_3), LinearLayout.LayoutParams(dp(24), dp(24)))

        steps += Step(badge, badgeIcon, badgeNumber, detail)
        return row
    }

    private lateinit var homeRow: View
    private lateinit var officeRow: View
    private lateinit var keyRow: View
    private lateinit var wakeInfo: TextView

    /** Downloads the offline voice model "Hey James" needs (once, ~40 MB). */
    private fun downloadModel() {
        if (VoiceModel.isReady(this)) {
            toast("Voice model is ready. Say \"Hey James\" while driving.")
            return
        }
        if (VoiceModel.downloading) {
            toast("Already downloading…")
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Download voice model?")
            .setMessage("\"Hey James\" listens on your phone, without the internet, using a free English voice model. " +
                "It's about ${VoiceModel.SIZE_MB} MB, downloaded once. Wi-Fi recommended.")
            .setPositiveButton("Download") { _, _ ->
                val app = applicationContext
                VoiceModel.download(app) { ok ->
                    runOnUiThread {
                        if (ok) {
                            toast("Voice model ready. Say \"Hey James\"!")
                            JamesService.send(app, JamesService.ACTION_WAKE_RELOAD, "voice model ready")
                        } else {
                            toast("Download failed: ${VoiceModel.lastError}. Check the internet and try again.")
                        }
                        refresh()
                    }
                }
                refresh()
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(Ui.HAIRLINE)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            marginStart = dp(66)
        }
    }

    private fun matchWrap(top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
        }

    // ================= Live state =================

    private var carConnected = false
    private var tick = 0
    /** Starts true so opening the screen with the permission already on doesn't pop the overlay. */
    private var overlayWasOk = true

    @Volatile
    private var musicSummary: Pair<String, String> = "Reading music…" to ""
    private val io = Executors.newSingleThreadExecutor()

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    private fun hasBluetoothPermission() =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private fun ignoresBatteryOptimisation(): Boolean =
        getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true

    private fun refresh() {
        // Re-ask Android about the car every 2 seconds.
        if (tick++ % 2 == 0 && hasBluetoothPermission()) {
            CarConnection.check(this) { carConnected = it }
        }
        // Re-read the music library every 5 seconds, off the main thread.
        if (tick % 5 == 1 && !io.isShutdown) {
            io.execute {
                val summary = buildMusicSummary()
                runOnUiThread { musicSummary = summary }
            }
        }

        // Status card
        val running = JamesService.isRunning
        statusDot.background = Ui.oval(this, if (running) Ui.GREEN else Ui.GREY_DOT)
        statusTitle.text = if (running) "James is running" else "James is off"
        val car = Prefs.carName(this)
        statusCar.text = when {
            car == null -> "No car chosen yet"
            carConnected -> "$car · connected"
            else -> "$car · not connected"
        }
        val song = JamesService.nowPlaying
        statusSong.text = if (song != null) "♪  $song" else "Nothing playing"

        // Checklist
        val permsOk = neededPermissions().isEmpty()
        setStep(0, permsOk, if (permsOk) "All allowed" else "Bluetooth, notifications, music and microphone")
        setStep(1, car != null, car?.let { "$it · tap to change" } ?: "Pick your car's Bluetooth")
        val bgOk = ignoresBatteryOptimisation()
        setStep(2, bgOk, if (bgOk) "Allowed" else "Stop the phone from closing James")
        val overlayOk = Settings.canDrawOverlays(this)
        setStep(3, overlayOk, if (overlayOk) "Allowed" else "Needed for the bubble and the Music / Map screen")
        // Just granted while James is running: show the overlay now, no need to reconnect the car.
        if (overlayOk && !overlayWasOk && running) {
            JamesService.send(this, JamesService.ACTION_SHOW_OVERLAY, "overlay permission granted")
        }
        overlayWasOk = overlayOk

        // Wake word
        val ready = VoiceModel.isReady(this)
        setPlaceDetail(keyRow, when {
            ready -> "Ready"
            VoiceModel.downloading -> "Downloading… ${VoiceModel.percent.coerceAtLeast(0)}%"
            VoiceModel.lastError != null -> "Download failed · tap to try again"
            else -> "Not downloaded · tap to download"
        })
        val status = Prefs.wakeStatus(this)
        wakeInfo.text = when {
            !ready -> "Download the voice model once, then say \"Hey James\" any time while driving. Free, no account, works offline."
            status != null -> status
            else -> "Starts listening for \"Hey James\" the next time James starts."
        }

        // Places
        setPlaceDetail(homeRow, Prefs.home(this))
        setPlaceDetail(officeRow, Prefs.office(this))

        // Music
        musicDefault.text = musicSummary.first
        musicPlaylists.text = musicSummary.second
        musicPlaylists.visibility = if (musicSummary.second.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun setStep(index: Int, done: Boolean, detail: String) {
        val s = steps.getOrNull(index) ?: return
        s.badge.background = if (done) Ui.oval(this, Ui.GREEN) else Ui.oval(this, Color.TRANSPARENT, Ui.TEXT_3, 2)
        s.badgeIcon.visibility = if (done) View.VISIBLE else View.GONE
        s.badgeNumber.visibility = if (done) View.GONE else View.VISIBLE
        s.detail.text = detail
    }

    private fun buildMusicSummary(): Pair<String, String> {
        val folders = MusicLibrary.folders(this)
        val default = folders.firstOrNull { it.name.equals(MusicLibrary.DEFAULT_FOLDER, true) }
        val first = if (default != null) "${default.tracks.size} songs play on connect"
        else "No Music/${MusicLibrary.DEFAULT_FOLDER} folder yet"
        val others = folders.filter { it !== default }
        val second = if (others.isEmpty()) "No playlists yet"
        else "Playlists: " + others.joinToString(" · ") { "${it.name.replace('_', ' ')} (${it.tracks.size})" }
        return first to second
    }

    // ---------- Step 1: permissions ----------

    private fun neededPermissions(): List<String> {
        val wanted = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) wanted += Manifest.permission.BLUETOOTH_CONNECT
        wanted += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            wanted += Manifest.permission.POST_NOTIFICATIONS
            wanted += Manifest.permission.READ_MEDIA_AUDIO
        } else {
            wanted += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        val needed = neededPermissions()
        if (needed.isEmpty()) toast("All permissions already granted") else permissionLauncher.launch(needed.toTypedArray())
    }

    // ---------- Step 2: pick the car with the system "companion device" chooser ----------

    private fun chooseCar() {
        val cdm = getSystemService(CompanionDeviceManager::class.java)
        if (cdm == null) {
            toast("This phone doesn't support companion devices")
            return
        }
        val request = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothDeviceFilter.Builder().build())  // any Bluetooth device
            .setSingleDevice(false)
            .build()

        cdm.associate(request, object : CompanionDeviceManager.Callback() {
            @Deprecated("Still the callback on Android 10–12; Android 13+ forwards to it by default")
            override fun onDeviceFound(chooserLauncher: IntentSender) {
                this@SetupActivity.chooserLauncher.launch(IntentSenderRequest.Builder(chooserLauncher).build())
            }

            override fun onFailure(error: CharSequence?) {
                toast("Couldn't open the device list: $error")
            }
        }, null)
    }

    private fun onCarChosen(result: ActivityResult) {
        if (result.resultCode != Activity.RESULT_OK) return
        val data = result.data ?: return

        var address: String? = null
        var name: String? = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val info = data.getParcelableExtra(CompanionDeviceManager.EXTRA_ASSOCIATION, AssociationInfo::class.java)
            address = info?.deviceMacAddress?.toString()
            name = info?.displayName?.toString()
        }
        if (address == null) {
            @Suppress("DEPRECATION")
            val device = data.getParcelableExtra<Parcelable>(CompanionDeviceManager.EXTRA_DEVICE) as? BluetoothDevice
            address = device?.address
            if (name == null) name = try { device?.name } catch (e: SecurityException) { null }
        }
        if (address == null) {
            toast("Couldn't read that device. Try again.")
            return
        }

        val cdm = getSystemService(CompanionDeviceManager::class.java)
        val old = Prefs.carAddress(this)
        Prefs.saveCar(this, address, name)
        Log.i(JamesService.TAG, "Car saved: $name ($address)")

        // Android 12+: ask the system to wake CarPresenceService when this car comes and goes.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                if (old != null && !old.equals(address, true)) {
                    @Suppress("DEPRECATION") cdm.stopObservingDevicePresence(old)
                }
                @Suppress("DEPRECATION") cdm.startObservingDevicePresence(address)
            } catch (e: Exception) {
                Log.w(JamesService.TAG, "Presence observation failed: ${e.message}")
            }
        }
        toast("Saved ${name ?: address}. Turn the car's Bluetooth off and on to test.")
        refresh()
    }

    // ---------- Step 4: overlay ----------

    private fun openOverlaySettings() {
        if (Settings.canDrawOverlays(this)) {
            toast("Already allowed")
            return
        }
        overlayWasOk = false   // so the overlay pops up when they come back with it allowed
        toast("Turn on 'Allow display over other apps' for James")
        try {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            openAppSettings()
        }
    }

    // ---------- Step 3: background ----------

    private fun openBatterySettings() {
        toast("Find James and choose 'Don't optimise' / 'Unrestricted'")
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

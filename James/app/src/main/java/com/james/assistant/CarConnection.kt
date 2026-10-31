package com.james.assistant

import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Asks Android whether the saved car is connected right now, over the
 * audio (A2DP) or calls (Headset) profiles — the ones a car stereo uses.
 */
object CarConnection {

    private val ANY_PROFILE = intArrayOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)
    private val MUSIC_ONLY = intArrayOf(BluetoothProfile.A2DP)

    /**
     * Calls [onResult] on the main thread with true/false.
     * [musicReady] = true: only counts the music channel (A2DP). Cars often connect the calls
     * channel first; playing before the music channel is up would come out of the phone speaker.
     */
    fun check(ctx: Context, musicReady: Boolean = false, onResult: (Boolean) -> Unit) {
        val PROFILES = if (musicReady) MUSIC_ONLY else ANY_PROFILE
        val main = Handler(Looper.getMainLooper())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            main.post { onResult(false) }
            return
        }
        val car = Prefs.carAddress(ctx)
        val adapter = try {
            ctx.getSystemService(BluetoothManager::class.java)?.adapter?.takeIf { it.isEnabled }
        } catch (_: SecurityException) { null } // permission may change between the check and the call
        if (car == null || adapter == null) {
            main.post { onResult(false) }
            return
        }

        var pending = PROFILES.size
        var found = false
        var done = false
        fun finish() {
            if (done) return
            done = true
            val result = found
            main.post { onResult(result) }
        }

        for (profile in PROFILES) {
            val ok = try {
                adapter.getProfileProxy(ctx.applicationContext, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                        try {
                            if (!done && proxy.connectedDevices.any { it.address.equals(car, ignoreCase = true) }) found = true
                        } catch (e: SecurityException) {
                            Log.w(JamesService.TAG, "No Bluetooth permission: ${e.message}")
                        } finally {
                            try { adapter.closeProfileProxy(p, proxy) } catch (_: SecurityException) {}
                        }
                        if (--pending == 0 || found) finish()
                    }

                    override fun onServiceDisconnected(p: Int) {}
                }, profile)
            } catch (e: Exception) {
                false
            }
            if (!ok && --pending == 0) finish()
        }
        // Safety net in case a profile never answers.
        main.postDelayed({ finish() }, 1500)
    }
}

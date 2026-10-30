package com.james.assistant

import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Asks Android whether the saved car is connected right now, over the
 * audio (A2DP) or calls (Headset) profiles — the ones a car stereo uses.
 */
object CarConnection {

    private val PROFILES = intArrayOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)

    /** Calls [onResult] on the main thread with true/false. */
    fun check(ctx: Context, onResult: (Boolean) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        val car = Prefs.carAddress(ctx)
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
        if (car == null || adapter == null || !adapter.isEnabled) {
            main.post { onResult(false) }
            return
        }

        var pending = PROFILES.size
        var found = false
        var done = false
        fun finish() {
            if (done) return
            done = true
            main.post { onResult(found) }
        }

        for (profile in PROFILES) {
            val ok = try {
                adapter.getProfileProxy(ctx.applicationContext, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                        try {
                            if (proxy.connectedDevices.any { it.address.equals(car, ignoreCase = true) }) found = true
                        } catch (e: SecurityException) {
                            Log.w(JamesService.TAG, "No Bluetooth permission: ${e.message}")
                        } finally {
                            adapter.closeProfileProxy(p, proxy)
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

package com.james.assistant

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Fires on every Bluetooth link up/down. Only reacts to the saved car.
 * This is the trigger on Android 10/11, and a backup to CarPresenceService on 12+.
 */
class BluetoothCarReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
        val address = device?.address
        if (!Prefs.isCar(context, address)) return

        Log.i(JamesService.TAG, "ACL event ${intent.action} for car $address")
        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED ->
                JamesService.send(context, JamesService.ACTION_CONNECTED, "BluetoothCarReceiver")
            BluetoothDevice.ACTION_ACL_DISCONNECTED ->
                JamesService.send(context, JamesService.ACTION_DISCONNECTED, "BluetoothCarReceiver")
        }
    }
}

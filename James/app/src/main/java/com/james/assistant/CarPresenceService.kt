package com.james.assistant

import android.companion.CompanionDeviceService
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * Android 12+: the system binds this when the associated car connects or disconnects,
 * which is the officially allowed way to start James from the background.
 * (On Android 13+ the newer AssociationInfo callbacks forward to these by default.)
 */
@RequiresApi(31)
class CarPresenceService : CompanionDeviceService() {

    @Deprecated("Replaced by onDeviceAppeared(AssociationInfo) on API 33+, which calls this by default")
    override fun onDeviceAppeared(address: String) {
        Log.i(JamesService.TAG, "Companion device appeared: $address")
        if (Prefs.isCar(this, address)) {
            JamesService.send(this, JamesService.ACTION_CONNECTED, "CarPresenceService")
        }
    }

    @Deprecated("Replaced by onDeviceDisappeared(AssociationInfo) on API 33+, which calls this by default")
    override fun onDeviceDisappeared(address: String) {
        Log.i(JamesService.TAG, "Companion device disappeared: $address")
        if (Prefs.isCar(this, address)) {
            JamesService.send(this, JamesService.ACTION_DISCONNECTED, "CarPresenceService")
        }
    }
}

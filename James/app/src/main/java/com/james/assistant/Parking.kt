package com.james.assistant

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

/**
 * "Where did I park?": when the car's Bluetooth disconnects, James saves where the phone is.
 * A notification then offers walking directions back to the car, and setup shows "Find my car".
 *
 * Uses Android's own LocationManager (no Google Play services needed).
 * Needs location set to "Allow all the time", because it runs after the car has disconnected.
 */
object Parking {

    data class Spot(val lat: Double, val lng: Double, val accuracyM: Float, val timeMs: Long)

    private const val FILE = "james"
    private const val CHANNEL_ID = "james_parking"
    private const val NOTIFICATION_ID = 7
    private const val FRESH_FIX_TIMEOUT_MS = 8_000L
    private const val MAX_LAST_KNOWN_AGE_MS = 3 * 60_000L

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ---------------- Settings and permission ----------------

    fun isEnabled(ctx: Context) = prefs(ctx).getBoolean("parking_enabled", true)
    fun setEnabled(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("parking_enabled", on).apply()

    fun hasForegroundPermission(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** "Allow all the time": needed because the spot is saved after the car (and James's screen) is gone. */
    fun hasBackgroundPermission(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun isReady(ctx: Context) = isEnabled(ctx) && hasForegroundPermission(ctx) && hasBackgroundPermission(ctx)

    // ---------------- Saved spot ----------------

    fun last(ctx: Context): Spot? {
        val p = prefs(ctx)
        if (!p.contains("park_lat")) return null
        return Spot(
            java.lang.Double.longBitsToDouble(p.getLong("park_lat", 0)),
            java.lang.Double.longBitsToDouble(p.getLong("park_lng", 0)),
            p.getFloat("park_acc", 0f),
            p.getLong("park_time", 0)
        )
    }

    private fun save(ctx: Context, loc: Location) {
        prefs(ctx).edit()
            .putLong("park_lat", java.lang.Double.doubleToRawLongBits(loc.latitude))
            .putLong("park_lng", java.lang.Double.doubleToRawLongBits(loc.longitude))
            .putFloat("park_acc", loc.accuracy)
            .putLong("park_time", System.currentTimeMillis())
            .apply()
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().remove("park_lat").remove("park_lng").remove("park_acc").remove("park_time").apply()
        cancelNotification(ctx)
    }

    // ---------------- Capture (called when the car disconnects) ----------------

    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var capturing = false

    /** Gets a fresh location (up to 8 s), else a recent last-known one, saves it and notifies. */
    fun capture(ctx: Context) {
        val app = ctx.applicationContext
        if (!isEnabled(app) || capturing) return
        if (!hasForegroundPermission(app)) {
            Log.i(JamesService.TAG, "Parking spot not saved: no location permission")
            return
        }
        val lm = app.getSystemService(LocationManager::class.java) ?: return
        capturing = true
        var done = false
        fun finish(loc: Location?) {
            if (done) return
            done = true
            capturing = false
            val best = loc ?: bestLastKnown(lm)
            if (best == null) {
                Log.w(JamesService.TAG, "Parking spot not saved: no location available")
                return
            }
            save(app, best)
            Log.i(JamesService.TAG, "Parking spot saved (±${best.accuracy.toInt()} m)")
            showNotification(app)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val provider = when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && lm.hasProvider(LocationManager.FUSED_PROVIDER) &&
                        lm.isProviderEnabled(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
                    lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                    else -> null
                }
                if (provider == null) {
                    finish(null)
                    return
                }
                val cancel = CancellationSignal()
                lm.getCurrentLocation(provider, cancel, executor) { loc -> main.post { finish(loc) } }
                main.postDelayed({
                    if (!done) {
                        cancel.cancel()
                        finish(null)   // no fresh fix in time (e.g. underground): use the last known one
                    }
                }, FRESH_FIX_TIMEOUT_MS)
            } else {
                finish(null)
            }
        } catch (e: SecurityException) {
            Log.w(JamesService.TAG, "Location refused: ${e.message}")
            capturing = false
        } catch (e: Exception) {
            Log.w(JamesService.TAG, "Location failed: ${e.message}")
            finish(null)
        }
    }

    private fun bestLastKnown(lm: LocationManager): Location? {
        val now = System.currentTimeMillis()
        return try {
            lm.getProviders(true)
                .mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (_: SecurityException) { null } }
                .filter { now - it.time < MAX_LAST_KNOWN_AGE_MS }
                .minByOrNull { it.accuracy }
        } catch (e: Exception) {
            null
        }
    }

    // ---------------- Find my car ----------------

    /** Walking directions back to the car in Google Maps (or any maps app). */
    fun directionsIntent(spot: Spot): Intent {
        val maps = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${spot.lat},${spot.lng}&mode=w"))
            .setPackage("com.google.android.apps.maps")
        return maps.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun fallbackIntent(spot: Spot): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("geo:${spot.lat},${spot.lng}?q=${spot.lat},${spot.lng}(My car)"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Opens directions; falls back to any maps app if Google Maps isn't installed. */
    fun openDirections(ctx: Context): Boolean {
        val spot = last(ctx) ?: return false
        return try {
            ctx.startActivity(directionsIntent(spot))
            true
        } catch (e: Exception) {
            try {
                ctx.startActivity(fallbackIntent(spot))
                true
            } catch (e2: Exception) {
                false
            }
        }
    }

    fun timeLabel(ctx: Context, spot: Spot): String {
        val now = System.currentTimeMillis()
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(spot.timeMs))
        val days = ((now - spot.timeMs) / 86_400_000L).toInt()
        return when (days) {
            0 -> "today at $time"
            1 -> "yesterday at $time"
            else -> DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(spot.timeMs))
        }
    }

    // ---------------- Notification ----------------

    private fun showNotification(ctx: Context) {
        val spot = last(ctx) ?: return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Where I parked", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    setSound(null, null)
                }
            )
        }
        val open = PendingIntent.getActivity(
            ctx, 70, directionsIntent(spot),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_car)
            .setContentTitle("Your car is parked here")
            .setContentText("Parked ${timeLabel(ctx, spot)} · Tap for walking directions")
            .setContentIntent(open)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .build()
        try {
            nm.notify(NOTIFICATION_ID, n)
        } catch (e: SecurityException) {
            Log.w(JamesService.TAG, "Can't show the parking notification: ${e.message}")
        }
    }

    /** Called when the car connects again: the old spot no longer matters. */
    fun cancelNotification(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }
}

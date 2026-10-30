package com.james.assistant

import android.content.Context

/** Remembers which Bluetooth device is "the car". */
object Prefs {
    private const val FILE = "james"
    private const val KEY_ADDRESS = "car_address"
    private const val KEY_NAME = "car_name"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun carAddress(ctx: Context): String? = prefs(ctx).getString(KEY_ADDRESS, null)
    fun carName(ctx: Context): String? = prefs(ctx).getString(KEY_NAME, null)

    fun saveCar(ctx: Context, address: String, name: String?) {
        prefs(ctx).edit()
            .putString(KEY_ADDRESS, address.uppercase())
            .putString(KEY_NAME, name ?: address)
            .apply()
    }

    // ---------- Where the music stopped, so it can carry on next time ----------

    data class Resume(val folder: String, val trackUri: String, val positionMs: Long)

    fun saveResume(ctx: Context, folder: String, trackUri: String, positionMs: Long) {
        prefs(ctx).edit()
            .putString("resume_folder", folder)
            .putString("resume_uri", trackUri)
            .putLong("resume_pos", positionMs.coerceAtLeast(0))
            .apply()
    }

    fun loadResume(ctx: Context): Resume? {
        val p = prefs(ctx)
        val folder = p.getString("resume_folder", null) ?: return null
        val uri = p.getString("resume_uri", null) ?: return null
        return Resume(folder, uri, p.getLong("resume_pos", 0))
    }

    // ---------- Places: saved Home / Office, and the last 3 trips ----------

    fun home(ctx: Context): String? = prefs(ctx).getString("place_home", null)?.takeIf { it.isNotBlank() }
    fun office(ctx: Context): String? = prefs(ctx).getString("place_office", null)?.takeIf { it.isNotBlank() }

    fun setHome(ctx: Context, address: String?) =
        prefs(ctx).edit().putString("place_home", address?.trim()).apply()

    fun setOffice(ctx: Context, address: String?) =
        prefs(ctx).edit().putString("place_office", address?.trim()).apply()

    fun recents(ctx: Context): List<String> =
        prefs(ctx).getString("recent_places", null)
            ?.split('\n')?.filter { it.isNotBlank() }.orEmpty()

    fun addRecent(ctx: Context, address: String) {
        val clean = address.trim()
        if (clean.isEmpty()) return
        val list = (listOf(clean) + recents(ctx).filterNot { it.equals(clean, ignoreCase = true) }).take(3)
        prefs(ctx).edit().putString("recent_places", list.joinToString("\n")).apply()
    }

    // ---------- Wake word ("Hey James") ----------

    fun picovoiceKey(ctx: Context): String? =
        prefs(ctx).getString("picovoice_key", null)?.trim()?.takeIf { it.isNotEmpty() }

    fun setPicovoiceKey(ctx: Context, key: String?) =
        prefs(ctx).edit().putString("picovoice_key", key?.trim()).apply()

    /** Last wake-word status, written by the service and shown on the setup screen. */
    fun wakeStatus(ctx: Context): String? = prefs(ctx).getString("wake_status", null)

    fun setWakeStatus(ctx: Context, status: String?) =
        prefs(ctx).edit().putString("wake_status", status).apply()

    /** True if [address] is the saved car (MAC addresses compared case-insensitively). */
    fun isCar(ctx: Context, address: String?): Boolean {
        val saved = carAddress(ctx) ?: return false
        return address != null && address.equals(saved, ignoreCase = true)
    }
}

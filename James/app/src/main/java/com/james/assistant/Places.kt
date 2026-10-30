package com.james.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A saved place, e.g. ("Church", "Daystar Christian Centre, Oregun"). */
data class Place(val name: String, val address: String)

/**
 * The driver's saved places: as many as they like, any names.
 * Saying "take me to <name>" (or tapping it in Quick picks) goes to its address.
 * Main thread only.
 */
object Places {

    private const val FILE = "james"
    private const val KEY = "places_v2"
    const val MAX = 12

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Place> {
        val p = prefs(ctx)
        val raw = p.getString(KEY, null) ?: return migrateOldHomeOffice(ctx)
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = o.optString("name").trim()
                val address = o.optString("address").trim()
                if (name.isEmpty() || address.isEmpty()) null else Place(name, address)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(ctx: Context, list: List<Place>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("name", it.name).put("address", it.address)) }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    /** Adds a place, or updates it if [oldName] (or the same name) already exists. */
    fun put(ctx: Context, place: Place, oldName: String? = null) {
        val list = all(ctx).toMutableList()
        val i = list.indexOfFirst {
            it.name.equals(oldName ?: place.name, ignoreCase = true)
        }
        if (i >= 0) list[i] = place else list += place
        save(ctx, list)
    }

    fun remove(ctx: Context, name: String) {
        save(ctx, all(ctx).filterNot { it.name.equals(name, ignoreCase = true) })
    }

    /**
     * Finds a saved place from what was said: "church", "my church", "the office".
     * "work" also finds a place called "Office" and the other way round; "house" finds "Home".
     */
    fun find(ctx: Context, spoken: String): Place? {
        val t = spoken.lowercase().trim().removePrefix("my ").removePrefix("the ").trim()
        if (t.isEmpty()) return null
        val list = all(ctx)
        list.firstOrNull { it.name.lowercase() == t }?.let { return it }
        val synonyms = mapOf(
            "house" to "home", "home" to "house",
            "work" to "office", "office" to "work"
        )
        synonyms[t]?.let { alt -> list.firstOrNull { it.name.lowercase() == alt }?.let { return it } }
        return null
    }

    /** Before this version, only Home and Office existed: carry them over once. */
    private fun migrateOldHomeOffice(ctx: Context): List<Place> {
        val list = buildList {
            Prefs.home(ctx)?.let { add(Place("Home", it)) }
            Prefs.office(ctx)?.let { add(Place("Office", it)) }
        }
        save(ctx, list)
        return list
    }
}

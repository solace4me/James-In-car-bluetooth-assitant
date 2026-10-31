package com.james.assistant

import java.util.Locale

/**
 * Picks a female text-to-speech voice, so Azarah sounds the same on every phone.
 *
 * Plain Kotlin with no Android in it, so it's covered by fast unit tests (VoicePickerTest).
 *
 * Android doesn't say which voices are female, so they're recognised by name:
 *  - Google's speech engine (most phones): voices like "en-us-x-sfg-local". The letters after
 *    "-x-" name the speaker; the female ones are listed in [GOOGLE_FEMALE].
 *  - Samsung's engine: "en-US-SMTf00" (f = female, m = male).
 *  - Any engine whose voice name says "female".
 * If the phone has no recognisable female voice, the phone's default voice is kept.
 */
object VoicePicker {

    /** What the picker needs to know about one installed voice. */
    data class Option(
        val name: String,
        val locale: Locale,
        val needsNetwork: Boolean,
        /** False while the voice is listed but not downloaded yet. */
        val installed: Boolean
    )

    /** Google speech-engine speaker codes that are female (English voices). */
    private val GOOGLE_FEMALE = setOf(
        "sfg", "tpf", "tpc", "iob", "iog",   // US
        "gba", "gbc", "gbg",                 // UK
        "aua", "auc",                        // Australia
        "ahp", "cxx", "ene",                 // India
        "tpg"
    )

    fun isFemale(name: String): Boolean {
        val n = name.lowercase(Locale.ROOT)
        if ("female" in n) return true
        if ("male" in n) return false
        if (Regex("smtf\\d").containsMatchIn(n)) return true   // Samsung
        val code = Regex("-x-([a-z]{3})").find(n)?.groupValues?.get(1) ?: return false
        return code in GOOGLE_FEMALE
    }

    /**
     * The best female voice for [wanted] (the phone's language), or null to keep the default.
     * Prefers: same language and country, then same language (e.g. a US voice on a Nigerian
     * English phone); voices that work offline (in a car the signal comes and goes); installed ones.
     */
    fun pick(options: List<Option>, wanted: Locale): String? {
        val female = options.filter { it.installed && isFemale(it.name) && it.locale.language == wanted.language }
        if (female.isEmpty()) return null
        return female.sortedWith(
            compareBy<Option>(
                { it.locale.country != wanted.country },      // same country first
                { it.needsNetwork },                          // offline first
                { it.locale.country != "US" && it.locale.country != "GB" }, // then the most complete voices
                { it.name }                                   // stable choice
            )
        ).first().name
    }
}

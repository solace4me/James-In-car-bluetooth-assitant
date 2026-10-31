package com.james.assistant

import java.util.Locale

/**
 * Turns what the driver said into something James should do.
 *
 * Plain Kotlin with no Android in it, so it's covered by fast unit tests
 * (app/src/test/java/com/james/assistant/CommandsTest.kt).
 */
sealed interface Command {
    /** Go somewhere: a saved place name ("church") or an address ("Computer Village Ikeja"). */
    data class Navigate(val destination: String) : Command
    data object OpenMap : Command
    data object OpenMusic : Command
    data object Shuffle : Command
    data object Play : Command
    data object Pause : Command
    data object Next : Command
    data object Previous : Command
    /** "Play gospel": play the playlist with (roughly) this name. */
    data class PlayNamed(val name: String) : Command
    data object Close : Command
    /** During the Go countdown only. */
    data object Go : Command
    data object Cancel : Command
    /** Nothing matched; it may still be a bare playlist name ("gospel"). */
    data class Unknown(val text: String) : Command
}

object Commands {

    /** Command words only. Payloads use [speechText] so addresses keep their punctuation. */
    fun normalize(s: String): String =
        speechText(s)
            .replace(Regex("[^\\p{L}\\p{N}' ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun speechText(s: String): String =
        s.lowercase(Locale.ROOT).replace('’', '\'').replace('‘', '\'')
            .trim()
            .replace(WAKE_PREFIX, "")
            .replace(Regex("^please\\s+"), "")
            .replace(Regex("\\s+please[.!?]*$"), "")
            .replace(Regex("\\s+"), " ")
            .trim().trimEnd('.', '!', '?')

    /** Number of single-letter edits to turn [a] into [b] (Levenshtein distance). */
    fun editDistance(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }

    /**
     * What to do after the wake word.
     * [duringCountdown]: the Go countdown is running, so "go" / "cancel" / "stop" mean the trip.
     * [savedPlaces]: names of the driver's saved places, so "church" alone means "take me to church".
     */
    fun parse(said: String, duringCountdown: Boolean = false, savedPlaces: List<Place> = emptyList()): Command {
        val text = speechText(said)
        val t = normalize(said)
        if (duringCountdown) {
            if (t in GO_WORDS) return Command.Go
            if (t in CANCEL_WORDS) return Command.Cancel
        }
        val destination = DESTINATION_PREFIXES.firstOrNull { text.startsWith(it) }
            ?.let { text.removePrefix(it).trim() }?.takeIf { it.isNotEmpty() }
        return when {
            destination != null -> Command.Navigate(destination)
            t in HOME_WORDS -> Command.Navigate("home")
            matchPlace(text, savedPlaces) != null -> Command.Navigate(text)
            t in MAP_WORDS -> Command.OpenMap
            t in MUSIC_WORDS -> Command.OpenMusic
            t in SHUFFLE_WORDS -> Command.Shuffle
            t in PLAY_WORDS -> Command.Play
            t in PAUSE_WORDS -> Command.Pause
            t in NEXT_WORDS -> Command.Next
            t in PREV_WORDS -> Command.Previous
            text.startsWith("play ") -> Command.PlayNamed(text.removePrefix("play ").trim())
            t in CLOSE_WORDS -> Command.Close
            else -> Command.Unknown(text)
        }
    }

    /**
     * Finds a saved place from what was said: "church", "my church", "the office".
     * "work" also finds a place called "Office" and the other way round; "house" finds "Home".
     */
    fun matchPlace(spoken: String, places: List<Place>): Place? {
        fun key(name: String) = normalizeFolder(name).removePrefix("my ").removePrefix("the ").trim()
        val t = key(spoken)
        if (t.isEmpty()) return null
        places.firstOrNull { key(it.name) == t }?.let { return it }
        PLACE_SYNONYMS[t]?.let { alt -> places.firstOrNull { key(it.name) == alt }?.let { return it } }
        return null
    }

    /** "Take me to Lekki Phase 1." -> "Lekki Phase 1" (keeps the original capitals). */
    fun stripDestination(said: String): String {
        var t = said.trim().trimEnd('.', '!', '?')
        ADDRESS_PREFIXES.firstOrNull { t.lowercase().startsWith(it) }?.let { t = t.substring(it.length).trim() }
        return t
    }

    /** "home", "my office", "the house"…: a saved place the driver may not have set up yet. */
    fun isHomeOrOfficeWord(text: String): Boolean =
        text.lowercase().trim().removePrefix("my ").removePrefix("the ").removePrefix("go ").trim() in
            setOf("home", "house", "office", "work")

    /**
     * Matches a spoken playlist name to one of [folderNames]: exact, then one contains the other,
     * then a near spelling (about 1 wrong letter in 4). "James_Default" is heard as "james default".
     */
    fun matchFolder(spoken: String, folderNames: List<String>): String? {
        val key = normalizeFolder(spoken)
        if (key.isEmpty()) return null
        val named = folderNames.map { it to normalizeFolder(it.replace('_', ' ')) }.filter { it.second.isNotEmpty() }
        named.firstOrNull { it.second == key }?.let { return it.first }
        if (key.length < 3) return null
        // Part of the name ("fuji" for "Fuji Classics"); 3+ letters so "hi" doesn't match "Chill".
        named.firstOrNull {
            it.second.contains(key) || (it.second.length >= 3 && key.length >= 4 && key.contains(it.second))
        }?.let { return it.first }
        return named.map { it.first to editDistance(it.second, key) }
            .filter { (name, d) -> normalizeFolder(name).length >= 3 && d <= maxOf(1, key.length / 4) }
            .minByOrNull { it.second }?.first
    }

    /** Like [normalize], but keeps the word "james" (a folder may be called "James Default"). */
    private fun normalizeFolder(s: String): String =
        s.lowercase(Locale.ROOT).replace('’', '\'').replace('‘', '\'')
            .replace(Regex("[^\\p{L}\\p{N}' ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /**
     * The wake word, if the driver repeats it to the speech recogniser: "Hey Azarah, next song",
     * "Azarah play gospel". "James" only counts after a greeting, so a "James Default" playlist still works.
     */
    private val WAKE_PREFIX = Regex(
        "^(?:(?:hey|okay|ok)\\s+james|(?:(?:hey|hello|hi|okay|ok)\\s+)?(?:azarah|azara|azaria|a zara|ah zara))\\b[\\s,!:.-]*"
    )

    private val PLACE_SYNONYMS = mapOf("house" to "home", "home" to "house", "work" to "office", "office" to "work")
    private val GO_WORDS = setOf("go", "go now", "yes", "start", "let's go", "lets go", "ok go", "okay go", "drive", "go go")
    private val CANCEL_WORDS = setOf("cancel", "stop", "no", "don't", "dont", "never mind", "nevermind", "wait")
    private val CLOSE_WORDS = setOf("close", "hide", "nothing", "never mind", "nevermind", "cancel", "goodbye", "bye", "that's all", "thats all", "no")
    private val MAP_WORDS = setOf("map", "maps", "navigation", "navigate", "directions", "where to", "open map", "open maps", "trip", "start a trip")
    private val MUSIC_WORDS = setOf("music", "playlist", "playlists", "songs", "my music", "open music", "choose music", "change music", "change playlist")
    private val SHUFFLE_WORDS = setOf("shuffle", "shuffle all", "shuffle all music", "shuffle all songs", "shuffle all my songs", "shuffle music", "shuffle songs", "shuffle my music", "shuffle my songs")
    private val PLAY_WORDS = setOf("play", "resume", "continue", "play music", "start music", "play the music", "resume music")
    private val PAUSE_WORDS = setOf("pause", "stop", "stop music", "pause music", "stop the music", "quiet", "silence")
    private val NEXT_WORDS = setOf("next", "next song", "skip", "skip song", "skip this", "next track", "change song", "change the song")
    private val PREV_WORDS = setOf("previous", "previous song", "back", "go back", "last song", "previous track")
    private val HOME_WORDS = setOf("home", "go home", "my house")
    private val DESTINATION_PREFIXES = listOf(
        "take me to ", "navigate to ", "directions to ", "drive to ", "go to ", "let's go to ", "lets go to ",
        "we are going to ", "we're going to ", "i'm going to ", "im going to ", "take me "
    )
    private val ADDRESS_PREFIXES = listOf(
        "take me to ", "navigate to ", "directions to ", "drive to ", "go to ", "let's go to ",
        "we are going to ", "we're going to ", "i'm going to ", "to "
    )
}

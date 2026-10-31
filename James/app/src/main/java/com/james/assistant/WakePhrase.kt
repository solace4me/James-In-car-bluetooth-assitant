package com.james.assistant

import org.json.JSONArray

/**
 * The wake word, "Azarah" (Hebrew for "help"), and what counts as hearing it.
 *
 * Plain Kotlin with no Android in it, so it's covered by fast unit tests (WakePhraseTest).
 *
 * The offline model only knows English words, and "azarah" isn't one, so it's listened for as
 * English words with the same sounds: "a zara" (ah-ZAH-rah), plus "ah zara" and "azaria".
 *
 * Vosk is told it may only hear the phrases in [grammarJson]. Besides the wake word, that list has:
 *  - greetings ("hey", "hello"…) and commands ("play the music"…), so "Azarah, play the music"
 *    works in one breath, with no "I'm listening" step;
 *  - decoys: everyday words that sound a bit like the wake word ("Sarah", "Sahara", "area"…).
 *    Without them, Vosk has to squeeze any speech into the nearest allowed phrase, and ordinary
 *    talk and music came out as the wake word. With them, near-misses land on a decoy instead.
 *
 * A wake only counts when the wake words are heard with good confidence AND either a greeting
 * came first ("hey / hello / hi / okay Azarah") or a known command follows ("Azarah, next song").
 * A lone "…zara" sound in conversation is ignored.
 *
 * Checked against the real model (vosk-model-small-en-us-0.15) with synthetic speech:
 * every word below is in its vocabulary, and 0 of 144 everyday sentences woke it.
 */
object WakePhrase {

    /** What to tell the driver to say. */
    const val PHRASE = "Hey Azarah"

    /** Vosk's per-word confidence (0..1) that the wake words must reach. Raise it if it still wakes by itself. */
    const val MIN_CONFIDENCE = 0.6f

    /** One recognised word and Vosk's confidence in it. */
    data class Word(val text: String, val conf: Float)

    /**
     * The wake word was heard. [command] is what followed it in the same breath
     * ("play the music"), or null: James should ask and listen.
     */
    data class Match(val command: String?)

    private val WAKE = listOf(listOf("a", "zara"), listOf("ah", "zara"), listOf("azaria"))
    private val GREETINGS = setOf("hey", "hello", "hi", "okay")

    /** Said right after the wake word, these are carried out straight away (see Commands.parse). */
    private val COMMANDS = listOf(
        "play the music", "play music", "play", "pause", "stop", "resume", "continue",
        "next", "next song", "skip", "previous", "previous song", "shuffle", "shuffle all",
        "music", "map", "open map", "take me home", "go home", "close", "never mind", "cancel", "go"
    )

    /** "Azarah, take me to …": the place itself can't be heard by the wake listener, so open the map and ask. */
    private val NAVIGATE_PREFIXES = listOf("take me to", "navigate to", "go to")

    private val DECOYS = listOf(
        "sarah", "sahara", "zara", "area", "a zero", "azure", "hazard", "a", "and", "i'm", "yeah",
        "what", "where", "that", "this", "is", "it", "we", "you", "no", "yes", "oh", "so", "there",
        "now", "here", "they", "to", "me"
    )

    /** The phrase list handed to Vosk. "[unk]" = anything else. */
    fun grammarJson(): String {
        val phrases = (WAKE.map { it.joinToString(" ") } + GREETINGS + COMMANDS + NAVIGATE_PREFIXES + DECOYS)
            .toSortedSet() + "[unk]"
        return JSONArray(phrases.toList()).toString()
    }

    /** Reads Vosk's result JSON (needs Recognizer.setWords(true)) into words with confidence. */
    fun words(json: String?): List<Word> {
        if (json.isNullOrEmpty()) return emptyList()
        return try {
            val arr = org.json.JSONObject(json).optJSONArray("result") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Word(o.optString("word"), o.optDouble("conf", 0.0).toFloat())
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Decides whether [words] (one finished utterance) is the wake word, and what to do. */
    fun match(words: List<Word>): Match? {
        val tokens = words.map { it.text }
        for (i in tokens.indices) {
            for (wake in WAKE) {
                if (i + wake.size > tokens.size || tokens.subList(i, i + wake.size) != wake) continue
                val confidence = words.subList(i, i + wake.size).minOf { it.conf }
                if (confidence < MIN_CONFIDENCE) continue
                val greeted = i > 0 && tokens[i - 1] in GREETINGS
                val rest = tokens.drop(i + wake.size).filter { it != "[unk]" }.joinToString(" ")
                when {
                    NAVIGATE_PREFIXES.any { rest.startsWith(it) } -> return Match("map")
                    rest in COMMANDS -> return Match(rest)
                    rest.isEmpty() && greeted -> return Match(null)
                }
            }
        }
        return null
    }
}

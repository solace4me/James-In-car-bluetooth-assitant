package com.james.assistant

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * When "Azarah" counts as the wake word. The word/confidence lists are what the real offline
 * model (vosk-model-small-en-us-0.15) produced for these phrases. Run: gradlew test
 */
@RunWith(RobolectricTestRunner::class)   // for org.json
class WakePhraseTest {

    private fun heard(vararg pairs: Pair<String, Double>) = pairs.map { WakePhrase.Word(it.first, it.second.toFloat()) }
    private fun heard(text: String, conf: Double = 1.0) = text.split(" ").map { WakePhrase.Word(it, conf.toFloat()) }

    @Test fun greetingThenWakeWordAsksAndListens() {
        assertEquals(WakePhrase.Match(null), WakePhrase.match(heard("hey" to 0.68, "a" to 0.81, "zara" to 1.0)))
        assertEquals(WakePhrase.Match(null), WakePhrase.match(heard("hello ah zara")))
        assertEquals(WakePhrase.Match(null), WakePhrase.match(heard("okay a zara")))
        assertEquals(WakePhrase.Match(null), WakePhrase.match(heard("hi azaria")))
        assertEquals(WakePhrase.Match(null), WakePhrase.match(heard("hey a zara [unk]")))
    }

    @Test fun wakeWordWithACommandDoesItInOneBreath() {
        assertEquals(WakePhrase.Match("play the music"), WakePhrase.match(heard("a zara play the music")))
        assertEquals(WakePhrase.Match("next song"), WakePhrase.match(heard("hey a zara next song")))
        assertEquals(WakePhrase.Match("pause"), WakePhrase.match(heard("a zara pause")))
        assertEquals(WakePhrase.Match("take me home"), WakePhrase.match(heard("a zara take me home")))
        assertEquals(WakePhrase.Match("shuffle"), WakePhrase.match(heard("hello a zara shuffle")))
    }

    @Test fun takeMeToSomewhereOpensTheMapToAsk() {
        assertEquals(WakePhrase.Match("map"), WakePhrase.match(heard("a zara take me to [unk]")))
        assertEquals(WakePhrase.Match("map"), WakePhrase.match(heard("hey a zara navigate to [unk] [unk]")))
    }

    @Test fun everydayTalkDoesNotWakeIt() {
        // What the model heard for "is Sarah coming with us" and "as far as I know"
        assertNull(WakePhrase.match(heard("a zara [unk]")))
        assertNull(WakePhrase.match(heard("a zara hi no")))
        // "hey Sarah play the song", "hello Zara how are you", "the Sahara desert is hot"
        assertNull(WakePhrase.match(heard("hey zara play the song")))
        assertNull(WakePhrase.match(heard("hello [unk] you")))
        assertNull(WakePhrase.match(heard("sahara never [unk]")))
        assertNull(WakePhrase.match(heard("play the music")))
        assertNull(WakePhrase.match(emptyList()))
    }

    @Test fun lowConfidenceIsIgnored() {
        assertNull(WakePhrase.match(heard("hey" to 0.9, "a" to 0.5, "zara" to 0.56)))
        assertNull(WakePhrase.match(heard("a" to 0.34, "zara" to 0.57, "pause" to 1.0)))
    }

    @Test fun readsVoskResultJson() {
        val json = """{"result":[{"conf":0.81,"end":1.2,"start":0.9,"word":"a"},{"conf":1.0,"end":1.6,"start":1.2,"word":"zara"},""" +
            """{"conf":1.0,"end":2.0,"start":1.7,"word":"pause"}],"text":"a zara pause"}"""
        val words = WakePhrase.words(json)
        assertEquals(listOf("a", "zara", "pause"), words.map { it.text })
        assertEquals(0.81f, words[0].conf, 0.001f)
        assertEquals(WakePhrase.Match("pause"), WakePhrase.match(words))
        assertTrue(WakePhrase.words("""{"text":""}""").isEmpty())
        assertTrue(WakePhrase.words("not json").isEmpty())
    }

    @Test fun grammarHasTheWakeWordCommandsAndUnknown() {
        val arr = JSONArray(WakePhrase.grammarJson())
        val phrases = (0 until arr.length()).map { arr.getString(it) }
        assertTrue(phrases.containsAll(listOf("a zara", "ah zara", "azaria", "hey", "hello", "play the music", "sarah", "[unk]")))
        assertEquals(phrases.size, phrases.toSet().size)
    }

    @Test fun everyWakeCommandIsUnderstood() {
        // Each command the wake listener can pass on must mean something to Commands.parse.
        for (c in listOf("play the music", "play music", "play", "pause", "stop", "resume", "continue", "next",
            "next song", "skip", "previous", "previous song", "shuffle", "shuffle all", "music", "map",
            "open map", "take me home", "go home", "close", "never mind", "cancel")) {
            assertFalse(c, Commands.parse(c) is Command.Unknown)
        }
        assertEquals(Command.Go, Commands.parse("go", duringCountdown = true))
    }
}

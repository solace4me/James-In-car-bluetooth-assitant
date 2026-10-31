package com.james.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Playlist names, saved places and spoken addresses. Run: gradlew test */
class MatchingTest {

    // ---------- Playlists ----------

    private val folders = listOf("James_Default", "Gospel", "Afrobeats", "Chill", "Fuji Classics")

    @Test fun folder_exactNameWhateverTheCase() {
        assertEquals("Gospel", Commands.matchFolder("gospel", folders))
        assertEquals("Gospel", Commands.matchFolder("GOSPEL", folders))
    }

    @Test fun folder_underscoresAreHeardAsSpaces() {
        assertEquals("James_Default", Commands.matchFolder("james default", folders))
    }

    @Test fun folder_partOfTheName() {
        assertEquals("Fuji Classics", Commands.matchFolder("fuji", folders))
        assertEquals("James_Default", Commands.matchFolder("default", folders))
    }

    @Test fun folder_nearSpellingFromSpeechRecognition() {
        assertEquals("Afrobeats", Commands.matchFolder("afro beats", folders))
        assertEquals("Afrobeats", Commands.matchFolder("afrobeat", folders))
        assertEquals("Gospel", Commands.matchFolder("gospal", folders))
    }

    @Test fun folder_noMatch() {
        assertNull(Commands.matchFolder("heavy metal", folders))
        assertNull(Commands.matchFolder("", folders))
        assertNull(Commands.matchFolder("gospel", emptyList()))
    }

    @Test fun folder_shortWordsDontMatchByAccident() {
        assertNull(Commands.matchFolder("hi", folders))
        assertNull(Commands.matchFolder("hi", listOf("Hit")))
        assertNull(Commands.matchFolder("a", listOf("AB")))
        assertNull(Commands.matchFolder("play ab", listOf("AB")))
        assertEquals("AB", Commands.matchFolder("ab", listOf("AB")))
    }

    // ---------- Saved places ----------

    private val places = listOf(
        Place("Home", "12 Allen Avenue, Ikeja"),
        Place("Office", "Plot 5, Admiralty Way, Lekki"),
        Place("Church", "Daystar Christian Centre, Oregun")
    )

    @Test fun place_byNameWithOrWithoutMyOrThe() {
        assertEquals("Church", Commands.matchPlace("church", places)?.name)
        assertEquals("Church", Commands.matchPlace("my church", places)?.name)
        assertEquals("Office", Commands.matchPlace("the office", places)?.name)
        assertEquals("Home", Commands.matchPlace("  HOME ", places)?.name)
    }

    @Test fun place_synonyms() {
        assertEquals("Office", Commands.matchPlace("work", places)?.name)
        assertEquals("Home", Commands.matchPlace("house", places)?.name)
    }

    @Test fun place_notSaved() {
        assertNull(Commands.matchPlace("gym", places))
        assertNull(Commands.matchPlace("", places))
        assertNull(Commands.matchPlace("home", emptyList()))
    }

    @Test fun place_normalizesPunctuationUnicodeAndSpacingOnBothSides() {
        val custom = listOf(Place("St. Mary’s", "123 Example Road"), Place("Café", "456 Other Road"))
        assertEquals(custom[0], Commands.matchPlace("st mary's", custom))
        assertEquals(custom[0], Commands.matchPlace("the St.   Mary's", custom))
        assertEquals(custom[1], Commands.matchPlace("café", custom))
    }

    // ---------- Spoken addresses ----------

    @Test fun address_dropsTakeMeToButKeepsCapitals() {
        assertEquals("Computer Village Ikeja", Commands.stripDestination("Take me to Computer Village Ikeja."))
        assertEquals("Lekki Phase 1", Commands.stripDestination("navigate to Lekki Phase 1"))
        assertEquals("Ojuelegba", Commands.stripDestination("Ojuelegba"))
    }

    @Test fun address_homeAndOfficeWords() {
        assertTrue(Commands.isHomeOrOfficeWord("home"))
        assertTrue(Commands.isHomeOrOfficeWord("my office"))
        assertTrue(Commands.isHomeOrOfficeWord("the house"))
        assertFalse(Commands.isHomeOrOfficeWord("Computer Village"))
    }
}

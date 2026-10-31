package com.james.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

/** What James does after "Hey James" for the things a driver actually says. Run: gradlew test */
class CommandsTest {

    private val places = listOf(
        Place("Home", "12 Allen Avenue, Ikeja"),
        Place("Office", "Plot 5, Admiralty Way, Lekki"),
        Place("Church", "Daystar Christian Centre, Oregun")
    )

    private fun parse(said: String, countdown: Boolean = false) =
        Commands.parse(said, duringCountdown = countdown, savedPlaces = places)

    // ---------- Cleaning up what was heard ----------

    @Test fun normalize_dropsWakeWordPunctuationAndCase() {
        assertEquals("next song", Commands.normalize("Hey James, NEXT song!"))
        assertEquals("music", Commands.normalize("okay james music please"))
        assertEquals("take me to computer village", Commands.normalize("  Take me to   Computer-Village. "))
    }

    @Test fun editDistance_countsSingleLetterEdits() {
        assertEquals(0, Commands.editDistance("gospel", "gospel"))
        assertEquals(1, Commands.editDistance("gospel", "gospels"))
        assertEquals(1, Commands.editDistance("gospel", "gospal"))
        assertEquals(6, Commands.editDistance("", "gospel"))
    }

    // ---------- Music ----------

    @Test fun musicWords_openThePlaylists() {
        assertEquals(Command.OpenMusic, parse("music"))
        assertEquals(Command.OpenMusic, parse("change playlist"))
    }

    @Test fun playbackControls() {
        assertEquals(Command.Play, parse("play"))
        assertEquals(Command.Play, parse("resume music"))
        assertEquals(Command.Pause, parse("pause"))
        assertEquals(Command.Next, parse("skip this"))
        assertEquals(Command.Previous, parse("previous song"))
        assertEquals(Command.Shuffle, parse("shuffle all my songs"))
    }

    @Test fun stop_pausesMusicOutsideTheCountdown() {
        assertEquals(Command.Pause, parse("stop"))
    }

    @Test fun playName_playsThatPlaylist() {
        assertEquals(Command.PlayNamed("gospel"), parse("Play Gospel"))
        assertEquals(Command.PlayNamed("afro beats"), parse("play afro beats"))
    }

    @Test fun unknownWords_mayBeABarePlaylistName() {
        assertEquals(Command.Unknown("afrobeats"), parse("Afrobeats"))
    }

    // ---------- Maps and places ----------

    @Test fun mapWords_openTheMap() {
        assertEquals(Command.OpenMap, parse("map"))
        assertEquals(Command.OpenMap, parse("where to"))
    }

    @Test fun takeMeTo_navigatesStraightAway() {
        assertEquals(Command.Navigate("computer village ikeja"), parse("Take me to Computer Village Ikeja"))
        assertEquals(Command.Navigate("lekki phase 1"), parse("navigate to Lekki Phase 1"))
    }

    @Test fun home_navigatesHome() {
        assertEquals(Command.Navigate("home"), parse("take me home"))
        assertEquals(Command.Navigate("home"), parse("go home"))
        assertEquals(Command.Navigate("home"), parse("home"))
    }

    @Test fun aSavedPlaceNameAlone_navigatesThere() {
        assertEquals(Command.Navigate("church"), parse("church"))
        assertEquals(Command.Navigate("my office"), parse("my office"))
    }

    @Test fun aWordThatIsNotASavedPlace_doesNotNavigate() {
        assertEquals(Command.Unknown("gym"), parse("gym"))
    }

    // ---------- The Go countdown ----------

    @Test fun duringCountdown_goAndCancelControlTheTrip() {
        assertEquals(Command.Go, parse("go", countdown = true))
        assertEquals(Command.Go, parse("yes", countdown = true))
        assertEquals(Command.Cancel, parse("cancel", countdown = true))
        assertEquals(Command.Cancel, parse("stop", countdown = true))
    }

    @Test fun outsideCountdown_cancelAndNoJustClose() {
        assertEquals(Command.Close, parse("cancel"))
        assertEquals(Command.Close, parse("never mind"))
        assertEquals(Command.Close, parse("no"))
    }

    @Test fun duringCountdown_otherCommandsStillWork() {
        assertEquals(Command.Next, parse("next", countdown = true))
    }

    @Test fun namesKeepJamesAndPoliteWordsInsideDestinations() {
        assertEquals(Command.Navigate("james street"), parse("Hey James, take me to James Street please"))
        assertEquals(Command.PlayNamed("james brown"), parse("play James Brown"))
        assertEquals(Command.Unknown("james brown"), parse("James Brown"))
        assertEquals(Command.PlayNamed("please please me"), parse("play Please Please Me"))
        assertEquals(Command.Navigate("12 james st., apartment #4"), parse("take me to 12 James St., Apartment #4"))
    }

    @Test fun savedPlaceNamesUseTheSameCleaningAsSpeech() {
        val custom = listOf(Place("St. Mary’s", "123 Example Road"), Place("Café", "456 Other Road"))
        assertEquals(Command.Navigate("st. mary's"), Commands.parse("St. Mary's", savedPlaces = custom))
        assertEquals(Command.Navigate("café"), Commands.parse("Café", savedPlaces = custom))
    }

    @Test fun shuffleOnlyMatchesAnActualCommand() {
        assertEquals(Command.PlayNamed("shuffle classics"), parse("play Shuffle Classics"))
        assertEquals(Command.Unknown("reshuffled"), parse("reshuffled"))
    }

    @Test fun wakeWordRepeatedToTheRecogniserIsIgnored() {
        assertEquals(Command.Next, Commands.parse("Hey Azarah, next song"))
        assertEquals(Command.PlayNamed("gospel"), Commands.parse("Azarah play gospel"))
        assertEquals(Command.OpenMap, Commands.parse("hello azara map"))
        // "James" without a greeting is still a word, e.g. the James Default playlist.
        assertEquals(Command.PlayNamed("james default"), Commands.parse("play James Default"))
    }
}

package com.james.assistant

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

/** Azarah's voice is female on every phone that has one. Run: gradlew test */
class VoicePickerTest {

    private fun v(name: String, tag: String, network: Boolean = false, installed: Boolean = true) =
        VoicePicker.Option(name, Locale.forLanguageTag(tag), network, installed)

    @Test fun recognisesFemaleVoicesByName() {
        assertTrue(VoicePicker.isFemale("en-us-x-sfg-local"))
        assertTrue(VoicePicker.isFemale("en-gb-x-gba-network"))
        assertTrue(VoicePicker.isFemale("en-US-SMTf00"))
        assertTrue(VoicePicker.isFemale("en-us-x-sfg#female_2-local"))
        assertFalse(VoicePicker.isFemale("en-us-x-iom-local"))
        assertFalse(VoicePicker.isFemale("en-gb-x-rjs-local"))
        assertFalse(VoicePicker.isFemale("en-US-SMTm00"))
        assertFalse(VoicePicker.isFemale("en-us-x-sfg#male_1-local"))
        assertFalse(VoicePicker.isFemale("en-US-language"))
    }

    @Test fun prefersSameCountryOfflineFemaleVoice() {
        val voices = listOf(
            v("en-us-x-iom-local", "en-US"),
            v("en-us-x-sfg-network", "en-US", network = true),
            v("en-us-x-sfg-local", "en-US"),
            v("en-gb-x-gba-local", "en-GB")
        )
        assertEquals("en-us-x-sfg-local", VoicePicker.pick(voices, Locale.US))
        assertEquals("en-gb-x-gba-local", VoicePicker.pick(voices, Locale.UK))
    }

    @Test fun nigerianEnglishFallsBackToAnotherEnglishFemaleVoice() {
        val voices = listOf(v("en-us-x-iol-local", "en-US"), v("en-gb-x-gbc-local", "en-GB"), v("en-us-x-tpf-local", "en-US"))
        assertNotNull(VoicePicker.pick(voices, Locale.forLanguageTag("en-NG")))
        assertTrue(VoicePicker.isFemale(VoicePicker.pick(voices, Locale.forLanguageTag("en-NG"))!!))
    }

    @Test fun skipsVoicesNotDownloadedOrInAnotherLanguage() {
        val voices = listOf(v("en-us-x-sfg-local", "en-US", installed = false), v("fr-fr-x-frc-local", "fr-FR"), v("en-us-x-iom-local", "en-US"))
        assertNull(VoicePicker.pick(voices, Locale.US))   // keeps the phone's default
    }
}

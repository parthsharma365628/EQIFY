package com.example.eqify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashScrubberTest {
    private val r = CrashScrubber.REDACTED

    @Test fun redactsSecretsCaseInsensitively() {
        val out = CrashScrubber.scrub("Lookup failed for Blinding Lights by THE WEEKND",
            listOf("blinding lights", "The Weeknd"))
        assertEquals("Lookup failed for $r by $r", out)
    }

    @Test fun redactsUrlQueryStrings() {
        val out = CrashScrubber.scrub(
            "GET https://itunes.apple.com/search?term=Blinding+Lights&entity=song failed", emptyList())
        assertEquals("GET https://itunes.apple.com/search?$r failed", out)
    }

    @Test fun keepsUrlWithoutQueryAndFragment() {
        val text = "Profile https://example.github.io/eqify-data/profiles/ab/cd.json#x missing"
        assertEquals(text, CrashScrubber.scrub(text, emptyList()))
    }

    @Test fun ignoresBlankAndVeryShortSecrets() {
        val text = "A failure in band 3"
        assertEquals(text, CrashScrubber.scrub(text, listOf("", "  ", "A", "in")))
    }

    @Test fun longerSecretWinsOverItsPrefix() {
        val out = CrashScrubber.scrub("Track: ABBA Gold", listOf("ABB", "ABBA Gold"))
        assertEquals("Track: $r", out)
    }

    @Test fun apiKeyInsideTextIsRemoved() {
        val key = "0123456789abcdef0123456789abcdef"
        val out = CrashScrubber.scrub("Auth failed for key $key", listOf(key))!!
        assertFalse(out.contains(key))
        assertTrue(out.endsWith(r))
    }

    @Test fun nullAndEmptyPassThrough() {
        assertNull(CrashScrubber.scrub(null, listOf("secret")))
        assertEquals("", CrashScrubber.scrub("", listOf("secret")))
    }

    @Test fun unchangedWhenNothingMatches() {
        val text = "java.lang.IllegalStateException: Equalizer released"
        assertEquals(text, CrashScrubber.scrub(text, listOf("Some Song", "Sony WH-1000XM5")))
    }
}

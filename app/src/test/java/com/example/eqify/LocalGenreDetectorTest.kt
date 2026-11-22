package com.example.eqify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression tests for the Pop/Rock flicker (2026-11-22): substring matching made
 * the instant offline guess wrong for ordinary titles, and a no-match default was
 * published before every real answer.
 */
class LocalGenreDetectorTest {

    @Test fun wholeWordKeywordsStillMatch() {
        assertEquals("Rock", LocalGenreDetector.match("Rock and Roll", "Some Band"))
        assertEquals("Hip-Hop", LocalGenreDetector.match("HUMBLE.", "Kendrick Lamar"))
        assertEquals("Metal", LocalGenreDetector.match("One", "Metallica"))
        assertEquals("Classical", LocalGenreDetector.match("Nocturne Op. 9 No. 2", "Frédéric Chopin"))
        assertEquals("K-Pop", LocalGenreDetector.match("LOVE DIVE", "IVE"))
    }

    @Test fun punctuationCountsAsAWordBoundary() {
        assertEquals("Rock", LocalGenreDetector.match("Thunderstruck", "AC/DC"))
        assertEquals("Hip-Hop", LocalGenreDetector.match("Praise the Lord", "A\$AP Rocky"))
        assertEquals("R&B", LocalGenreDetector.match("Focus", "H.E.R."))
    }

    @Test fun substringsInsideOtherWordsNoLongerMatch() {
        // "ive" (K-pop group) inside Live/Drive/Alive
        assertNull(LocalGenreDetector.match("Live Forever", "Unknown Artist"))
        assertNull(LocalGenreDetector.match("Drive", "Unknown Artist"))
        // "rock" inside Rocket, "tool" inside Stool, "sia" inside Asian, "nct" inside Instinct
        assertNull(LocalGenreDetector.match("Rocket Ship", "Unknown Artist"))
        assertNull(LocalGenreDetector.match("Bar Stool", "Unknown Artist"))
        assertNull(LocalGenreDetector.match("Asian Skies", "Unknown Artist"))
        assertNull(LocalGenreDetector.match("Instinct", "Unknown Artist"))
    }

    @Test fun keywordsAreCaseInsensitiveIncludingFormerlyUppercaseOnes() {
        assertEquals("Rock", LocalGenreDetector.match("All My Friends", "LCD Soundsystem"))
        assertEquals("Rock", LocalGenreDetector.match("All My Friends", "lcd soundsystem"))
    }

    @Test fun noMatchReturnsNullButDetectKeepsTheLastResortDefault() {
        assertNull(LocalGenreDetector.match("Untitled", "Unknown Artist"))
        assertEquals(LocalGenreDetector.DEFAULT_GENRE, LocalGenreDetector.detect("Untitled", "Unknown Artist"))
        assertEquals("Pop", LocalGenreDetector.DEFAULT_GENRE)
    }

    @Test fun ruleOrderStillDecidesTies() {
        // "metal" (Metal rule) is checked before "rock" (Rock rule).
        assertEquals("Metal", LocalGenreDetector.match("Heavy Metal Rock", "Unknown Artist"))
    }
}

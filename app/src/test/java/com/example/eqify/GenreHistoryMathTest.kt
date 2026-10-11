package com.example.eqify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenreHistoryMathTest {
    private val day = GenreHistoryMath.DAY_MS
    private val now = 30 * day

    private fun row(title: String, artist: String, genre: String, at: Long, id: Long = 1): GenreObservation {
        val key = TrackIdentity.of(title, artist)
        return GenreObservation(id, at, title, artist, key.title, key.artist, genre, "automatic")
    }

    @Test fun identityNormalizesWhitespaceAndUsesRootLocale() {
        assertEquals(TrackIdentity.of("  THE   SONG  ", " ARTIST\tNAME "), TrackIdentity.of("the song", "artist name"))
        assertTrue(TrackIdentity.of("Song", "Artist").valid)
        assertFalse(TrackIdentity.of(" ", "Artist").valid)
    }

    @Test fun correctionChoicesExcludeUtilityProfiles() {
        assertFalse("Flat" in EqProfileManager.correctionGenres)
        assertFalse("Bass Boost" in EqProfileManager.correctionGenres)
        assertTrue("Acoustic" in EqProfileManager.correctionGenres)
    }

    @Test fun correctionAppliesRetroactivelyWithoutChangingLoggedGenre() {
        val original = row(" Song ", "Artist", "Pop", now)
        val key = TrackIdentity.of("Song", "Artist")
        val correction = GenreCorrection(key.title, key.artist, "Song", "Artist", "Jazz", now)
        assertEquals("Pop", GenreHistoryMath.effectiveGenre(original, emptyMap()))
        assertEquals("Jazz", GenreHistoryMath.effectiveGenre(original, mapOf(key to correction)))
        assertEquals("Pop", original.loggedGenre)
    }

    @Test fun mixUsesSevenDayWindowAndExcludesFutureObservations() {
        val rows = listOf(
            row("A", "Artist", "Pop", now - 7 * day),
            row("B", "Artist", "Jazz", now - 7 * day - 1),
            row("C", "Artist", "Rock", now + 1)
        )
        val mix = GenreHistoryMath.mix(rows, emptyList(), now)
        assertEquals(1, mix.total)
        assertEquals("Pop", mix.items.single().genre)
        assertEquals(100, mix.items.single().percent)
    }

    @Test fun mixKeepsTopThreeAndCombinesRemainderDeterministically() {
        val rows = listOf("Pop", "Pop", "Jazz", "Rock", "Classical").mapIndexed { index, genre ->
            row("Song$index", "Artist", genre, now, index.toLong())
        }
        val mix = GenreHistoryMath.mix(rows, emptyList(), now)
        assertEquals(5, mix.total)
        assertEquals(listOf("Pop", "Classical", "Jazz", "Other"), mix.items.map { it.genre })
        assertEquals(listOf(40, 20, 20, 20), mix.items.map { it.percent })
        assertEquals(GenreMix(0, emptyList()), GenreHistoryMath.mix(emptyList(), emptyList(), now))
    }

    @Test fun recentUsesFourteenDayBoundaryAndOneRowPerTrack() {
        val rows = listOf(
            row("Song", "Artist", "Pop", now - day, 1),
            row(" song ", "ARTIST", "Jazz", now, 2),
            row("Old", "Artist", "Rock", now - 14 * day - 1, 3),
            row("Boundary", "Artist", "Jazz", now - 14 * day, 4)
        )
        val recent = GenreHistoryMath.recent(rows, emptyList(), now)
        assertEquals(listOf(" song ", "Boundary"), recent.map { it.title })
    }
}

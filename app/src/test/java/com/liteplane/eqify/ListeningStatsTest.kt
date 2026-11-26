package com.liteplane.eqify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class ListeningStatsTest {
    private val zone = ZoneOffset.UTC
    private val now = LocalDateTime.of(2026, 11, 24, 20, 0).toInstant(zone).toEpochMilli()
    private val day = GenreHistoryMath.DAY_MS
    private var nextId = 1L

    private fun obs(daysAgo: Double, genre: String, artist: String = "Artist", title: String = "Song${nextId}", hour: Int? = null): GenreObservation {
        var at = now - (daysAgo * day).toLong()
        if (hour != null) {
            val date = java.time.Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
            at = date.atTime(hour, 0).toInstant(zone).toEpochMilli()
        }
        val id = nextId++
        return GenreObservation(id, at, title, artist, title.lowercase(), artist.lowercase(), genre, "automatic")
    }

    @Test fun emptyHistory() {
        val s = ListeningStats.compute(emptyList(), emptyList(), now, zone)
        assertTrue(s.isEmpty); assertFalse(s.shareable)
        assertNull(s.previousSongs); assertNull(s.topGenre); assertNull(s.timeOfDay)
        assertEquals(7, s.dailyTopGenre.size)
    }

    @Test fun countsThisWeekAndComparesWithLastWeek() {
        val rows = listOf(obs(1.0, "Rock"), obs(2.0, "Rock"), obs(3.0, "Pop"), obs(8.0, "Pop"), obs(9.0, "Rock"), obs(20.0, "Jazz"))
        val s = ListeningStats.compute(rows, emptyList(), now, zone)
        assertEquals(3, s.songs)
        assertEquals(2, s.previousSongs)
        assertEquals("Rock", s.topGenre)
        assertEquals(listOf("Rock" to 67, "Pop" to 33), s.genres.map { it.genre to it.percent })
        assertEquals(17, s.genres[0].changePoints)   // 67% now vs 50% last week
        assertTrue(s.shareable)
    }

    @Test fun noLastWeekDataMeansNoComparison() {
        val s = ListeningStats.compute(listOf(obs(1.0, "Rock")), emptyList(), now, zone)
        assertNull(s.previousSongs)
        assertNull(s.genres.single().changePoints)
    }

    @Test fun correctionsAreUsedAndOtherSortsLast() {
        val rows = listOf(obs(1.0, "", title = "A"), obs(1.0, "", title = "B"), obs(1.0, "Pop", title = "C"))
        val fix = GenreCorrection("c", "artist", "C", "Artist", "Jazz", 0L)
        val s = ListeningStats.compute(rows, listOf(fix), now, zone)
        assertEquals(listOf("Jazz", "Other"), s.genres.map { it.genre })
        assertEquals("Jazz", s.topGenre)
    }

    @Test fun topArtistsGroupIgnoringCaseAndBreakTiesByName() {
        val rows = listOf(obs(1.0, "Pop", "Taylor"), obs(1.5, "Pop", "taylor"), obs(2.0, "Pop", "Adele"),
            obs(2.5, "Pop", "Björk"), obs(3.0, "Pop", "Björk"), obs(3.5, "Pop", "Cher"))
        val s = ListeningStats.compute(rows, emptyList(), now, zone)
        assertEquals(listOf("Björk" to 2, "Taylor" to 2, "Adele" to 1, "Cher" to 1), s.topArtists.map { it.artist to it.count })
    }

    @Test fun timeOfDayBuckets() {
        assertEquals(TimeOfDay.MORNING, ListeningStats.timeOfDay(5))
        assertEquals(TimeOfDay.AFTERNOON, ListeningStats.timeOfDay(12))
        assertEquals(TimeOfDay.EVENING, ListeningStats.timeOfDay(21))
        assertEquals(TimeOfDay.NIGHT, ListeningStats.timeOfDay(23))
        assertEquals(TimeOfDay.NIGHT, ListeningStats.timeOfDay(4))
        val rows = listOf(obs(1.0, "Pop", hour = 23), obs(2.0, "Pop", hour = 1), obs(3.0, "Pop", hour = 9))
        assertEquals(TimeOfDay.NIGHT, ListeningStats.compute(rows, emptyList(), now, zone).timeOfDay)
    }

    @Test fun dailyStripIsOldestFirstEndingToday() {
        val rows = listOf(obs(0.0, "Rock", hour = 10), obs(6.0, "Jazz", hour = 21), obs(6.0, "Jazz", hour = 22), obs(6.0, "Pop", hour = 22))
        val s = ListeningStats.compute(rows, emptyList(), now, zone)
        assertEquals(listOf("Jazz", null, null, null, null, null, "Rock"), s.dailyTopGenre)
    }
}

package com.example.eqify

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * "Your week in sound" (FEATURES_NEXT_PLAN.md §5), computed from detected-song
 * observations. Counts are detected songs, never listening time. Pure Kotlin.
 */
data class WeekStats(
    val songs: Int,
    /** Songs in the 7 days before this week, or null when there is no data that old. */
    val previousSongs: Int?,
    val genres: List<GenreShare>,
    val topArtists: List<ArtistCount>,
    val timeOfDay: TimeOfDay?,
    /** Oldest first, 7 entries: the most-detected genre that day, or null. */
    val dailyTopGenre: List<String?>
) {
    val topGenre: String? get() = genres.firstOrNull { !it.genre.equals("Other", true) }?.genre
    val isEmpty: Boolean get() = songs == 0
    /** Enough to be worth sharing. */
    val shareable: Boolean get() = songs >= ListeningStats.MIN_SONGS_TO_SHARE
}

data class GenreShare(val genre: String, val count: Int, val percent: Int, val changePoints: Int?)
data class ArtistCount(val artist: String, val count: Int)

enum class TimeOfDay(val label: String) { MORNING("Mornings"), AFTERNOON("Afternoons"), EVENING("Evenings"), NIGHT("Late nights") }

object ListeningStats {
    const val MIN_SONGS_TO_SHARE = 3
    private const val DAY = GenreHistoryMath.DAY_MS

    fun timeOfDay(hour: Int): TimeOfDay = when (hour) {
        in 5..11 -> TimeOfDay.MORNING
        in 12..16 -> TimeOfDay.AFTERNOON
        in 17..21 -> TimeOfDay.EVENING
        else -> TimeOfDay.NIGHT
    }

    fun compute(
        rows: List<GenreObservation>,
        corrections: List<GenreCorrection>,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): WeekStats {
        val lookup = corrections.associateBy { TrackIdentity(it.normalizedTitle, it.normalizedArtist) }
        fun genreOf(o: GenreObservation) = GenreHistoryMath.effectiveGenre(o, lookup).ifBlank { "Other" }

        val week = rows.filter { it.observedAtEpochMillis in (now - 7 * DAY + 1)..now }
        val before = rows.filter { it.observedAtEpochMillis in (now - 14 * DAY + 1)..(now - 7 * DAY) }

        val thisCounts = week.groupingBy(::genreOf).eachCount()
        val lastCounts = before.groupingBy(::genreOf).eachCount()
        val total = week.size
        val lastTotal = before.size
        val genres = thisCounts.entries
            .sortedWith(compareBy<Map.Entry<String, Int>> { it.key.equals("Other", true) }
                .thenByDescending { it.value }.thenBy { it.key })
            .map { (genre, count) ->
                val pct = percent(count, total)
                GenreShare(genre, count, pct,
                    changePoints = if (lastTotal > 0) pct - percent(lastCounts[genre] ?: 0, lastTotal) else null)
            }

        // Artists: case-insensitive grouping; show the most recent spelling.
        val artists = week.filter { it.artist.isNotBlank() }
            .groupBy { it.normalizedArtist.ifBlank { it.artist.lowercase() } }
            .map { (_, list) -> ArtistCount(list.maxBy { it.observedAtEpochMillis }.artist.trim(), list.size) }
            .sortedWith(compareByDescending<ArtistCount> { it.count }.thenBy { it.artist.lowercase() })
            .take(5)

        val time = week.groupingBy { timeOfDay(Instant.ofEpochMilli(it.observedAtEpochMillis).atZone(zone).hour) }
            .eachCount().entries
            .maxWithOrNull(compareBy<Map.Entry<TimeOfDay, Int>> { it.value }.thenByDescending { it.key.ordinal })?.key

        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val byDay: Map<LocalDate, List<GenreObservation>> =
            week.groupBy { Instant.ofEpochMilli(it.observedAtEpochMillis).atZone(zone).toLocalDate() }
        val daily = (6 downTo 0).map { back ->
            byDay[today.minusDays(back.toLong())]?.groupingBy(::genreOf)?.eachCount()
                ?.entries?.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                ?.firstOrNull()?.key
        }

        return WeekStats(
            songs = total,
            previousSongs = lastTotal.takeIf { rows.any { it.observedAtEpochMillis <= now - 7 * DAY } },
            genres = genres,
            topArtists = artists,
            timeOfDay = time,
            dailyTopGenre = daily
        )
    }

    private fun percent(count: Int, total: Int): Int = if (total == 0) 0 else (count * 100.0 / total + 0.5).toInt()
}

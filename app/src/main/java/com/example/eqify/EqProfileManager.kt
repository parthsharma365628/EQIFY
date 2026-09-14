package com.example.eqify

import kotlin.math.abs
import kotlin.math.log2

object EqProfileManager {

    val BAND_LABELS = arrayOf("60Hz", "170Hz", "310Hz", "600Hz", "1kHz", "3kHz", "6kHz", "12kHz")
    val BAND_CENTERS_HZ = intArrayOf(60, 170, 310, 600, 1_000, 3_000, 6_000, 12_000)

    val defaultProfiles: Map<String, FloatArray> = mapOf(
        "Flat"       to floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
        "Hip-Hop"    to floatArrayOf(6f, 4f, -1f, -1f, 2f, 1f, 0f, 1f),
        "EDM"        to floatArrayOf(6f, 3f, -1f, -2f, 0f, 2f, 4f, 5f),
        "Pop"        to floatArrayOf(2f, 1f, 1f, 2f, 3f, 2f, 2f, 3f),
        "Rock"       to floatArrayOf(4f, 2f, -1f, -2f, 1f, 4f, 4f, 3f),
        "Classical"  to floatArrayOf(0f, 0f, 1f, 1f, 1f, 1f, 3f, 4f),
        "Jazz"       to floatArrayOf(2f, 3f, 2f, 1f, 0f, 1f, 2f, 2f),
        "Blues"      to floatArrayOf(3f, 2f, 1f, 2f, 2f, 1f, 1f, 2f),
        "R&B"        to floatArrayOf(5f, 3f, 1f, 2f, 2f, 0f, 1f, 2f),
        "Soul"       to floatArrayOf(4f, 3f, 2f, 3f, 3f, 1f, 0f, 1f),
        "Acoustic"   to floatArrayOf(1f, 3f, 3f, 2f, 1f, 2f, 2f, 1f),
        "Metal"      to floatArrayOf(5f, 3f, -2f, -3f, 0f, 4f, 5f, 4f),
        "Podcast"    to floatArrayOf(-3f, -2f, 0f, 3f, 5f, 3f, 1f, 0f),
        "Bass Boost" to floatArrayOf(7f, 5f, 2f, 0f, 0f, 0f, 0f, 0f),
        "Afrobeats"  to floatArrayOf(5f, 3f, 1f, 0f, 1f, 2f, 3f, 3f),
        "Latin"      to floatArrayOf(4f, 3f, 1f, 1f, 2f, 2f, 3f, 3f),
        "K-Pop"      to floatArrayOf(2f, 2f, 2f, 3f, 4f, 3f, 3f, 4f),
        "Country"    to floatArrayOf(2f, 3f, 3f, 2f, 2f, 1f, 2f, 2f),
        "Ambient"    to floatArrayOf(2f, 1f, 0f, -1f, -1f, 1f, 3f, 4f),
    )

    private val genreKeywords = listOf(
        "afrobeats" to "Afrobeats",
        "afropop" to "Afrobeats",
        "hip hop" to "Hip-Hop",
        "hip-hop" to "Hip-Hop",
        "rap" to "Hip-Hop",
        "trap" to "Hip-Hop",
        "drill" to "Hip-Hop",
        "edm" to "EDM",
        "electronic" to "EDM",
        "house" to "EDM",
        "techno" to "EDM",
        "dubstep" to "EDM",
        "dance" to "EDM",
        "pop" to "Pop",
        "k-pop" to "K-Pop",
        "kpop" to "K-Pop",
        "synth pop" to "Pop",
        "rock" to "Rock",
        "punk" to "Rock",
        "grunge" to "Rock",
        "alternative" to "Rock",
        "indie rock" to "Rock",
        "metal" to "Metal",
        "heavy metal" to "Metal",
        "classical" to "Classical",
        "orchestral" to "Classical",
        "symphony" to "Classical",
        "jazz" to "Jazz",
        "blues" to "Blues",
        "swing" to "Jazz",
        "r&b" to "R&B",
        "rnb" to "R&B",
        "soul" to "Soul",
        "funk" to "R&B",
        "acoustic" to "Acoustic",
        "folk" to "Acoustic",
        "country" to "Country",
        "latin" to "Latin",
        "reggaeton" to "Latin",
        "podcast" to "Podcast",
        "spoken" to "Podcast",
        "audiobook" to "Podcast",
        "ambient" to "Ambient",
        "lo-fi" to "Ambient",
        "lofi" to "Ambient",
        "chillout" to "Ambient",
    )

    fun resolveGenreGains(genre: String): FloatArray {
        val trimmed = genre.trim()
        if (trimmed.isEmpty()) return defaultProfiles["Flat"]!!.copyOf()

        defaultProfiles[trimmed]?.let { return it.copyOf() }
        defaultProfiles.entries.find { it.key.equals(trimmed, ignoreCase = true) }?.value?.let {
            return it.copyOf()
        }

        val key = trimmed.lowercase()
        for ((needle, profileName) in genreKeywords) {
            if (key.contains(needle)) {
                return (defaultProfiles[profileName] ?: defaultProfiles["Flat"]!!).copyOf()
            }
        }

        return defaultProfiles["Flat"]!!.copyOf()
    }

    fun displayNameForGenre(genre: String): String {
        val trimmed = genre.trim()
        if (trimmed.isEmpty()) return "Flat"

        defaultProfiles.keys.find { it.equals(trimmed, ignoreCase = true) }?.let { return it }

        val key = trimmed.lowercase()
        for ((needle, profileName) in genreKeywords) {
            if (key.contains(needle)) return profileName
        }

        return trimmed.replaceFirstChar { if (it.isLowerCase()) it.uppercaseChar() else it }
    }

    fun supportedProfileForGenres(genres: List<String>): String? {
        for (genre in genres) {
            val displayName = displayNameForGenre(genre)
            if (defaultProfiles.containsKey(displayName) && displayName != "Flat") {
                return displayName
            }
        }
        return null
    }

    fun selectItunesGenre(
        results: List<ItunesTrack>,
        trackName: String,
        artistName: String
    ): String? {
        fun normalized(value: String?): String =
            value.orEmpty().lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

        val wantedTrack = normalized(trackName)
        val wantedArtist = normalized(artistName)
        val match = results
            .asSequence()
            .filter { it.kind == "song" && !it.primaryGenreName.isNullOrBlank() }
            .maxByOrNull { item ->
                val foundTrack = normalized(item.trackName)
                val foundArtist = normalized(item.artistName)
                var score = 0
                if (wantedTrack.isNotEmpty() && foundTrack == wantedTrack) score += 6
                else if (wantedTrack.isNotEmpty() && foundTrack.contains(wantedTrack)) score += 3
                if (wantedArtist.isNotEmpty() && foundArtist == wantedArtist) score += 4
                else if (wantedArtist.isNotEmpty() && foundArtist.contains(wantedArtist)) score += 2
                score
            } ?: return null

        return supportedProfileForGenres(listOf(match.primaryGenreName.orEmpty()))
    }

    fun combineGains(
        toneGains: FloatArray,
        headphoneCorrection: FloatArray,
        bassBoost: Float
    ): FloatArray = FloatArray(BAND_CENTERS_HZ.size) { i ->
        val bassContribution = when (i) {
            0 -> bassBoost
            1 -> bassBoost * 0.7f
            2 -> bassBoost * 0.4f
            else -> 0f
        }
        (toneGains.getOrElse(i) { 0f } +
            headphoneCorrection.getOrElse(i) { 0f } +
            bassContribution).coerceIn(-12f, 12f)
    }

    fun mapToDeviceBands(
        deviceBandCentersMilliHz: IntArray,
        gains: FloatArray,
        levelRange: ShortArray
    ): ShortArray {
        val minMb = levelRange[0].toInt()
        val maxMb = levelRange[1].toInt()
        return ShortArray(deviceBandCentersMilliHz.size) { bandIndex ->
            val deviceCenterHz = deviceBandCentersMilliHz[bandIndex] / 1_000.0
            if (deviceCenterHz <= 0) return@ShortArray 0
            val closestIdx = BAND_CENTERS_HZ.indices.minByOrNull { i ->
                if (BAND_CENTERS_HZ[i] <= 0) Double.MAX_VALUE
                else abs(log2(BAND_CENTERS_HZ[i].toDouble()) - log2(deviceCenterHz))
            } ?: 0
            (gains[closestIdx] * 100).toInt().coerceIn(minMb, maxMb).toShort()
        }
    }
}

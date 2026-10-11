package com.example.eqify

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Application-owned genre resolver. Runs even after MainActivity is destroyed. */
class GenreCoordinator(
    private val preferences: UserPreferencesRepository,
    private val history: GenreHistoryRepository,
    private val scope: CoroutineScope
) {
    private val _genreUiState = MutableStateFlow<GenreUiState>(GenreUiState.Idle)
    val genreUiState = _genreUiState.asStateFlow()
    private val revision = MutableStateFlow(0L)
    private val pendingMutation = MutableStateFlow(false)
    private val mutationLock = Mutex()
    private var loggedObservationId = -1L

    init {
        scope.launch {
            try { history.prune() } catch (e: Exception) { Log.w("GenreCoordinator", "History prune failed", e) }
        }
        scope.launch {
            val options = combine(
                preferences.autoGenreDetection,
                preferences.lastFmApiKey,
                NowPlayingState.mediaListenerEnabled
            ) { auto, key, listener -> LookupOptions(auto, key, listener) }
            combine(NowPlayingState.observation, options, revision, pendingMutation) { observation, config, version, pending ->
                Lookup(observation, config, version, pending)
            }.collectLatest { lookup -> process(lookup) }
        }
    }

    suspend fun setCorrection(title: String, artist: String, genre: String?) = mutationLock.withLock {
        pendingMutation.value = true // invalidate/cancel an in-flight network response first
        try {
            history.setCorrection(title, artist, genre)
            revision.value += 1
        } finally {
            pendingMutation.value = false
        }
    }

    suspend fun clearHistory() = mutationLock.withLock {
        pendingMutation.value = true
        try {
            history.clearAll()
            // Clearing should stay empty until a new accepted notification.
            NowPlayingState.observation.value?.let { loggedObservationId = it.id }
            revision.value += 1
        } finally {
            pendingMutation.value = false
        }
    }

    private suspend fun process(lookup: Lookup) {
        val obs = lookup.observation
        if (lookup.pending) return
        if (obs == null || !lookup.options.listener) {
            _genreUiState.value = GenreUiState.Idle
            NowPlayingState.updateCurrentGenre("")
            return
        }
        if (!lookup.options.auto) {
            _genreUiState.value = GenreUiState.DetectionOff
            NowPlayingState.updateCurrentGenre("")
            return
        }
        if (!TrackIdentity.of(obs.title, obs.artist).valid) return
        fun current() = NowPlayingState.observation.value?.id == obs.id &&
            revision.value == lookup.revision && !pendingMutation.value
        fun publish(result: GenreUiState.Detected) {
            if (!current()) return
            NowPlayingState.updateCurrentGenre(result.genre)
            _genreUiState.value = result
        }

        _genreUiState.value = GenreUiState.Detecting
        val correction = try { history.correction(obs.title, obs.artist) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { Log.w("GenreCoordinator", "Correction lookup failed", e); null }
        if (!current()) return
        val resolved = if (correction != null) {
            GenreUiState.Detected(correction.correctedGenre, "Manual correction", "User selected")
        } else resolve(obs.title, obs.artist, lookup.options.lastFmKey, ::publish)
        if (!current()) return
        publish(resolved)
        mutationLock.withLock {
            if (current() && loggedObservationId != obs.id) {
                loggedObservationId = obs.id // at most one row even if settings/revision re-emits
                try {
                    withContext(NonCancellable) {
                        history.record(obs.title, obs.artist, resolved.genre, correction != null, obs.observedAtEpochMillis)
                    }
                } catch (e: Exception) { Log.w("GenreCoordinator", "History write failed", e) }
            }
        }
    }

    private suspend fun resolve(
        track: String, artist: String, lastFmApiKey: String,
        publish: (GenreUiState.Detected) -> Unit
    ): GenreUiState.Detected {
        val cleanArtist = artist.substringBefore("•").substringBefore("·").trim()
        val local = GenreUiState.Detected(LocalGenreDetector.detect(track, cleanArtist), "Offline", "Low", true)
        publish(local)
        if (lastFmApiKey.isNotBlank()) {
            try {
                val result = LastFmClient.apiService.getArtistInfo(
                    method = "artist.getinfo", artist = cleanArtist,
                    apiKey = lastFmApiKey.trim(), format = "json", autocorrect = 1
                )
                val genre = EqProfileManager.supportedProfileForGenres(result.artist?.tags?.tag.orEmpty().map { it.name })
                if (genre != null) return GenreUiState.Detected(genre, "Last.fm", "High")
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Continue with iTunes. */ }
        }
        try {
            val response = ItunesClient.apiService.searchSongs(
                term = listOf(track.trim(), cleanArtist).filter { it.isNotBlank() }.joinToString(" ")
            )
            val genre = EqProfileManager.selectItunesGenre(response.results, track, cleanArtist)
            if (genre != null) return GenreUiState.Detected(genre, "iTunes", "Medium")
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* Keep local result and try backend. */ }
        return try {
            val response = RetrofitClient.apiService.resolveGenre(GenreRequest(track.trim(), cleanArtist))
            val source = response.source.lowercase()
            val fallback = "keyword" in source || "fallback" in source || source == "local"
            GenreUiState.Detected(
                response.genre,
                when (source) { "lastfm" -> "Last.fm"; "itunes" -> "iTunes"; "cache" -> "EQify cache"; else -> "Offline" },
                if (fallback) "Low" else "Medium", fallback
            )
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { local }
    }
}

private data class LookupOptions(val auto: Boolean, val lastFmKey: String, val listener: Boolean)
private data class Lookup(
    val observation: NowPlayingState.TrackObservation?,
    val options: LookupOptions,
    val revision: Long,
    val pending: Boolean
)

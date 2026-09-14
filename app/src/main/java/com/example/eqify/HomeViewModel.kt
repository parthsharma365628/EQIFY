package com.example.eqify

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as EqifyApplication).repository

    private val _genreUiState = MutableStateFlow<GenreUiState>(GenreUiState.Idle)
    val genreUiState: StateFlow<GenreUiState> = _genreUiState.asStateFlow()

    val currentTrack         = NowPlayingState.track
    val currentArtist        = NowPlayingState.artist
    val selectedHeadphone    = NowPlayingState.selectedHeadphone
    val mediaListenerEnabled = NowPlayingState.mediaListenerEnabled

    private val _testToneMessage = MutableStateFlow<String?>(null)
    val testToneMessage: StateFlow<String?> = _testToneMessage.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                NowPlayingState.track,
                NowPlayingState.artist,
                repository.autoGenreDetection,
                repository.lastFmApiKey
            ) { track, artist, autoGenreOn, lastFmApiKey ->
                GenreLookupState(track, artist, autoGenreOn, lastFmApiKey)
            }
                .collectLatest { state ->
                    val newTrack = state.track
                    val newArtist = state.artist
                    val autoGenreOn = state.autoGenreOn
                    val listenerOn = NowPlayingState.mediaListenerEnabled.value
                    val isPlaying  = newTrack != "Waiting for music…" && newTrack.isNotBlank()

                    when {
                        listenerOn && isPlaying && autoGenreOn && newArtist.isBlank() -> {
                            _genreUiState.value = GenreUiState.Detecting
                            NowPlayingState.updateCurrentGenre("")
                        }
                        listenerOn && isPlaying && autoGenreOn && newArtist.isNotBlank() -> {
                            _genreUiState.value = GenreUiState.Detecting
                            resolveGenre(newTrack, newArtist, state.lastFmApiKey)
                        }
                        listenerOn && isPlaying && !autoGenreOn -> {
                            NowPlayingState.updateCurrentGenre("")
                            _genreUiState.value = GenreUiState.DetectionOff
                        }
                        else -> {
                            _genreUiState.value = GenreUiState.Idle
                            NowPlayingState.updateCurrentGenre("")
                        }
                    }
                }
        }
    }

    fun setEqEnabled(enabled: Boolean) {
        viewModelScope.launch {
            EqState.setEnabled(enabled)
            repository.setEqEnabled(enabled)
        }
    }

    // ── Bass boost ────────────────────────────────────────────────────
    // Sets the level in EqState (picked up by EqProcessingService via combine)
    // and persists it so it survives app restarts.

    fun setBassBoost(level: Float) {
        viewModelScope.launch {
            EqState.setBassBoost(level)
            repository.setBassBoostLevel(level)
        }
    }

    fun playEqTestTone() {
        if (_testToneMessage.value == "Playing test sound...") return
        _testToneMessage.value = "Playing test sound..."
        viewModelScope.launch {
            val played = EqTestTonePlayer.play()
            _testToneMessage.value = if (played) {
                "Test complete: low, mid, and high tones"
            } else {
                "Test sound unavailable on this device"
            }
        }
    }

    private suspend fun resolveGenre(track: String, artist: String, lastFmApiKey: String) {
        val cleanArtist = cleanArtistString(artist)
        val localGenre = LocalGenreDetector.detect(track, cleanArtist)
        NowPlayingState.updateCurrentGenre(localGenre)
        _genreUiState.value = GenreUiState.Detected(
            genre = localGenre,
            source = "Offline",
            confidence = "Low",
            isFallback = true
        )

        if (lastFmApiKey.isNotBlank()) {
            try {
                val lastFmResponse = LastFmClient.apiService.getArtistInfo(
                    method = "artist.getinfo",
                    artist = cleanArtist,
                    apiKey = lastFmApiKey.trim(),
                    format = "json",
                    autocorrect = 1
                )
                val lastFmGenre = EqProfileManager.supportedProfileForGenres(
                    lastFmResponse.artist?.tags?.tag.orEmpty().map { it.name }
                )
                if (lastFmGenre != null) {
                    NowPlayingState.updateCurrentGenre(lastFmGenre)
                    _genreUiState.value = GenreUiState.Detected(
                        genre = lastFmGenre,
                        source = "Last.fm",
                        confidence = "High"
                    )
                    return
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Continue with the backend, which tries iTunes and local rules.
            }
        }

        try {
            val itunesResponse = ItunesClient.apiService.searchSongs(
                term = listOf(track.trim(), cleanArtist).filter { it.isNotBlank() }.joinToString(" ")
            )
            val itunesGenre = EqProfileManager.selectItunesGenre(
                itunesResponse.results,
                track,
                cleanArtist
            )
            if (itunesGenre != null) {
                NowPlayingState.updateCurrentGenre(itunesGenre)
                _genreUiState.value = GenreUiState.Detected(
                    genre = itunesGenre,
                    source = "iTunes",
                    confidence = "Medium"
                )
                return
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Keep the local result and try the configured backend once.
        }

        try {
            val response = RetrofitClient.apiService.resolveGenre(
                GenreRequest(
                    track  = track.trim(),
                    artist = cleanArtist
                )
            )
            NowPlayingState.updateCurrentGenre(response.genre)
            val normalizedSource = response.source.lowercase()
            val backendSource = when (normalizedSource) {
                "lastfm" -> "Last.fm"
                "itunes" -> "iTunes"
                "cache" -> "EQify cache"
                else -> "Offline"
            }
            val fallback =
                "keyword" in normalizedSource || "fallback" in normalizedSource ||
                    normalizedSource == "local"
            _genreUiState.value = GenreUiState.Detected(
                genre = response.genre,
                source = backendSource,
                confidence = if (fallback) "Low" else "Medium",
                isFallback = fallback
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Keep the immediate local result when the backend is unavailable.
        }
    }

    private fun cleanArtistString(raw: String): String =
        raw.substringBefore("•").substringBefore("·").trim()
}

private data class GenreLookupState(
    val track: String,
    val artist: String,
    val autoGenreOn: Boolean,
    val lastFmApiKey: String
)

sealed class GenreUiState {
    object Idle         : GenreUiState()
    object Detecting    : GenreUiState()
    object DetectionOff : GenreUiState()
    data class Detected(
        val genre: String,
        val source: String,
        val confidence: String,
        val isFallback: Boolean = false
    ) : GenreUiState()
    object Error        : GenreUiState()
}

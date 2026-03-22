package com.example.eqify

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as EqifyApplication).repository

    private val _genreUiState = MutableStateFlow<GenreUiState>(GenreUiState.Idle)
    val genreUiState: StateFlow<GenreUiState> = _genreUiState.asStateFlow()

    val currentTrack         = NowPlayingState.track
    val currentArtist        = NowPlayingState.artist
    val selectedHeadphone    = NowPlayingState.selectedHeadphone
    val mediaListenerEnabled = NowPlayingState.mediaListenerEnabled

    init {
        viewModelScope.launch {
            combine(
                NowPlayingState.track,
                NowPlayingState.artist,
                repository.autoGenreDetection
            ) { track, artist, autoGenreOn -> Triple(track, artist, autoGenreOn) }
                .collect { (newTrack, newArtist, autoGenreOn) ->
                    val listenerOn = NowPlayingState.mediaListenerEnabled.value
                    val isPlaying  = newTrack != "Waiting for music…" && newTrack.isNotBlank()

                    when {
                        listenerOn && isPlaying && autoGenreOn && newArtist.isBlank() -> {
                            _genreUiState.value = GenreUiState.Detecting
                            NowPlayingState.updateCurrentGenre("")
                        }
                        listenerOn && isPlaying && autoGenreOn && newArtist.isNotBlank() -> {
                            _genreUiState.value = GenreUiState.Detecting
                            fetchGenre(newTrack, newArtist)
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

    private fun fetchGenre(track: String, artist: String) {
        viewModelScope.launch {
            try {
                val response = RetrofitClient.apiService.resolveGenre(
                    GenreRequest(
                        track  = track.trim(),
                        artist = cleanArtistString(artist)
                    )
                )
                NowPlayingState.updateCurrentGenre(response.genre)
                _genreUiState.value = GenreUiState.Detected(response.genre)
            } catch (e: Exception) {
                e.printStackTrace()
                val local = LocalGenreDetector.detect(track, artist)
                NowPlayingState.updateCurrentGenre(local)
                _genreUiState.value = GenreUiState.Detected(local)
            }
        }
    }

    private fun cleanArtistString(raw: String): String =
        raw.substringBefore("•").substringBefore("·").trim()
}

sealed class GenreUiState {
    object Idle         : GenreUiState()
    object Detecting    : GenreUiState()
    object DetectionOff : GenreUiState()
    data class Detected(val genre: String) : GenreUiState()
    object Error        : GenreUiState()
}
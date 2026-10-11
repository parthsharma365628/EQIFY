package com.example.eqify

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as EqifyApplication
    private val repository = app.repository
    private val history = app.genreHistory

    val genreUiState = app.genreCoordinator.genreUiState
    val currentTrack = NowPlayingState.track
    val currentArtist = NowPlayingState.artist
    val selectedHeadphone = NowPlayingState.selectedHeadphone
    val mediaListenerEnabled = NowPlayingState.mediaListenerEnabled
    val recentTracks = history.recent().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val genreMix = history.mix().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GenreMix(0, emptyList()))
    val correctionGenres get() = EqProfileManager.correctionGenres

    private val _testToneMessage = MutableStateFlow<String?>(null)
    val testToneMessage = _testToneMessage.asStateFlow()
    private val _correctionMessage = MutableStateFlow<String?>(null)
    val correctionMessage = _correctionMessage.asStateFlow()

    fun correctGenre(title: String, artist: String, genre: String?) {
        viewModelScope.launch {
            try { app.genreCoordinator.setCorrection(title, artist, genre) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _correctionMessage.value = "Could not save genre correction: ${e.message ?: "unknown error"}" }
        }
    }

    fun dismissCorrectionMessage() { _correctionMessage.value = null }

    fun setEqEnabled(enabled: Boolean) {
        viewModelScope.launch {
            EqState.setEnabled(enabled)
            repository.setEqEnabled(enabled)
        }
    }

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
            _testToneMessage.value = if (EqTestTonePlayer.play()) {
                "Test complete: low, mid, and high tones"
            } else "Test sound unavailable on this device"
        }
    }
}

sealed class GenreUiState {
    object Idle : GenreUiState()
    object Detecting : GenreUiState()
    object DetectionOff : GenreUiState()
    data class Detected(
        val genre: String,
        val source: String,
        val confidence: String,
        val isFallback: Boolean = false
    ) : GenreUiState()
    object Error : GenreUiState()
}

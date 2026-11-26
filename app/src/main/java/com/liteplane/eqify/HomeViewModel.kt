package com.liteplane.eqify

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
    val weekStats = history.weekStats().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000),
        ListeningStats.compute(emptyList(), emptyList(), 0L))
    val genreMix = history.mix().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GenreMix(0, emptyList()))
    val correctionGenres get() = EqProfileManager.correctionGenres
    /** The user's own presets, offered in the genre-correction sheet next to the built-in genres. */
    val customPresetNames = repository.customPresetNames
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

    fun setGenreStrength(value: Float) {
        viewModelScope.launch { DeviceSoundCoordinator.setGenreStrength(value) }
    }

    /** Newer release not yet dismissed by the user (FEATURES_NEXT_PLAN.md §1). */
    val updateBanner = kotlinx.coroutines.flow.combine(UpdateChecker.latest, repository.dismissedUpdateTag) { latest, dismissed ->
        latest?.takeIf { it.tag != dismissed }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun dismissUpdate(tag: String) {
        viewModelScope.launch { repository.dismissUpdate(tag) }
    }

    fun setEqEnabled(enabled: Boolean) {
        viewModelScope.launch {
            EqState.setEnabled(enabled)
            repository.setEqEnabled(enabled)
        }
    }

    /** Edits the connected device's bass when it has its own (FEATURES_NEXT_PLAN.md §4). */
    fun setBassBoost(level: Float) {
        viewModelScope.launch { DeviceSoundCoordinator.setBass(level) }
    }

    val deviceSound = DeviceSoundCoordinator.state

    // ── Test sounds and sleep timer (FEATURES_BATCH3_PLAN.md §C, §D) ──
    val playingTone = EqTestTonePlayer.current
    val forceMono = repository.forceMono
    val sleepTimer = SleepTimer.state
    // Batch 4 (FEATURES_BATCH4_PLAN.md S6, L)
    val changes = ChangeLog.entries
    val safeListeningEnabled = repository.safeListeningEnabled
    val listeningDose = repository.listeningDose

    fun playTestTone(tone: TestTone) {
        viewModelScope.launch {
            _testToneMessage.value = if (EqTestTonePlayer.play(tone)) null
                else "Test sound unavailable on this device"
        }
    }

    /** The app is in the foreground, so it may start the service command directly. */
    private fun sendToService(configure: android.content.Intent.() -> Unit) {
        val intent = android.content.Intent(app, EqProcessingService::class.java).apply(configure)
        runCatching { androidx.core.content.ContextCompat.startForegroundService(app, intent) }
    }

    fun startSleepTimer(minutes: Int) = sendToService {
        action = EqProcessingService.ACTION_SLEEP_START
        putExtra(EqProcessingService.EXTRA_MINUTES, minutes)
    }

    fun cancelSleepTimer() = sendToService { action = EqProcessingService.ACTION_SLEEP_CANCEL }
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

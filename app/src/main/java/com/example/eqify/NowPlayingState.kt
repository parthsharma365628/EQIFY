package com.example.eqify

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Global bridge between [MediaListenerService] (which detects the playing track)
 * and the rest of the app (ViewModel, EqProcessingService).
 *
 * Added in this version: [currentGenre]
 * The genre is written by [GenreCoordinator] after detection,
 * and read by [EqProcessingService] to auto-select an EQ profile.
 */
object NowPlayingState {

    data class TrackObservation(
        val id: Long, val title: String, val artist: String,
        val observedAtEpochMillis: Long
    )
    private var nextObservationId = 0L
    private val _observation = MutableStateFlow<TrackObservation?>(null)
    val observation = _observation.asStateFlow()

    // ------------------------------------------------------------------
    // Track metadata
    // ------------------------------------------------------------------

    private val _track = MutableStateFlow("Waiting for music…")
    val track = _track.asStateFlow()

    private val _artist = MutableStateFlow("")
    val artist = _artist.asStateFlow()

    // ------------------------------------------------------------------
    // Genre (written by GenreCoordinator, read by EqProcessingService)
    // ------------------------------------------------------------------

    private val _currentGenre = MutableStateFlow("")
    val currentGenre = _currentGenre.asStateFlow()

    // ------------------------------------------------------------------
    // Headphone selection
    // ------------------------------------------------------------------

    private val _selectedHeadphone = MutableStateFlow("Sony WH-1000XM5")
    val selectedHeadphone = _selectedHeadphone.asStateFlow()

    // ------------------------------------------------------------------
    // Feature toggle
    // ------------------------------------------------------------------

    private val _mediaListenerEnabled = MutableStateFlow(true)
    val mediaListenerEnabled = _mediaListenerEnabled.asStateFlow()

    // ------------------------------------------------------------------
    // Mutators
    // ------------------------------------------------------------------

    /**
     * Called by [MediaListenerService] when a new track notification arrives.
     * Resets the genre so [EqProcessingService] knows to wait for the new one.
     */
    fun updateSong(newTrack: String, newArtist: String) {
        val changed = newTrack != _track.value || newArtist != _artist.value
        if (changed) {
            EqState.clearManualOverride()
            _currentGenre.value = ""
            _track.value = newTrack
            _artist.value = newArtist
        }
        // MediaListenerService deduplicates posted notifications. A newly
        // accepted occurrence may have the same metadata after listener restart.
        _observation.value = TrackObservation(++nextObservationId, newTrack, newArtist, System.currentTimeMillis())
    }

    /**
     * Called by [MediaListenerService] when music stops (notification removed).
     * Resets all "now playing" state to idle.
     */
    fun clearSong() {
        EqState.clearManualOverride()
        _track.value        = "Waiting for music…"
        _artist.value       = ""
        _currentGenre.value = ""
        _observation.value = null
    }

    /**
     * Called by [GenreCoordinator] as detection resolves.
     * [EqProcessingService] observes this and immediately recomputes the EQ profile.
     */
    fun updateCurrentGenre(genre: String) {
        _currentGenre.value = genre
    }

    fun updateSelectedHeadphone(name: String) {
        _selectedHeadphone.value = name
    }

    fun setMediaListenerEnabled(enabled: Boolean) {
        _mediaListenerEnabled.value = enabled
    }
}

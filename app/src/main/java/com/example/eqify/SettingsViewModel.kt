package com.example.eqify

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

/**
 * ViewModel for [com.example.eqify.screens.SettingsScreen].
 *
 * Extends [AndroidViewModel] so it can reach [EqifyApplication.repository]
 * without needing a DI framework.
 *
 * All state flows come directly from [UserPreferencesRepository] — DataStore
 * emits the last-saved value immediately on first collection, so the UI never
 * shows a stale default on cold start.
 *
 * Write methods persist to DataStore AND sync to the relevant runtime singleton
 * ([EqState], [NowPlayingState]) so changes take effect immediately without
 * requiring an app restart.
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as EqifyApplication).repository

    // ── Exposed flows (SettingsScreen collects these) ─────────────────

    val isEqEnabled          = repository.isEqEnabled
    val autoGenreDetection   = repository.autoGenreDetection
    val limitOutputGain      = repository.limitOutputGain
    val forceMono            = repository.forceMono
    val mediaListenerEnabled = repository.mediaListenerEnabled
    val headphoneAutoDetect  = repository.headphoneAutoDetect
    val lastFmApiKey          = repository.lastFmApiKey

    // ── Write methods ─────────────────────────────────────────────────

    fun setEqEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.setEqEnabled(enabled)
            EqState.setEnabled(enabled)        // runtime singleton stays in sync
        }
    }

    fun setAutoGenreDetection(enabled: Boolean) {
        viewModelScope.launch {
            repository.setAutoGenreDetection(enabled)
            // If turning off, clear the current genre so EqProcessingService
            // stops switching profiles and holds its current setting
            if (!enabled) NowPlayingState.updateCurrentGenre("")
        }
    }

    fun setLimitOutputGain(enabled: Boolean) {
        // Persisted to DataStore. EqProcessingService.observeAutoEqPipeline
        // observes repository.limitOutputGain in its combine — takes effect immediately.
        viewModelScope.launch { repository.setLimitOutputGain(enabled) }
    }

    fun setForceMono(enabled: Boolean) {
        // Persisted to DataStore. EqProcessingService.observeAutoEqPipeline
        // observes repository.forceMono in its combine — calls setMonoOutput() immediately.
        viewModelScope.launch { repository.setForceMono(enabled) }
    }

    /**
     * Updates both the persisted value and [NowPlayingState] so that
     * [MediaListenerService] and [HomeViewModel] see the change immediately.
     */
    fun setMediaListenerEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.setMediaListenerEnabled(enabled)
            NowPlayingState.setMediaListenerEnabled(enabled)
        }
    }

    fun setHeadphoneAutoDetect(enabled: Boolean) {
        viewModelScope.launch { repository.setHeadphoneAutoDetect(enabled) }
    }

    fun setLastFmApiKey(apiKey: String) {
        viewModelScope.launch { repository.setLastFmApiKey(apiKey) }
    }
}

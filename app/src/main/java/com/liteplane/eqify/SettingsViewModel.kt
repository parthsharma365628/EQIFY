package com.liteplane.eqify

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import com.liteplane.eqify.ui.theme.ThemeAccent
import com.liteplane.eqify.ui.theme.ThemeMode
import com.liteplane.eqify.ui.theme.ThemeStore

/**
 * ViewModel for [com.liteplane.eqify.screens.SettingsScreen].
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
    private val genreCoordinator = (application as EqifyApplication).genreCoordinator
    private val customProfiles = (application as EqifyApplication).customEqProfiles
    val customEqProfiles = customProfiles.profiles

    // ── Exposed flows (SettingsScreen collects these) ─────────────────

    val isEqEnabled          = repository.isEqEnabled
    val autoGenreDetection   = repository.autoGenreDetection
    val smoothEqTransitions  = repository.smoothEqTransitions
    val outputProtectionMode = repository.outputProtectionMode
    val limiterDiagnosticStatus = EqEngine.limiterDiagnosticStatus
    val forceMono            = repository.forceMono
    val mediaListenerEnabled = repository.mediaListenerEnabled
    val headphoneAutoDetect  = repository.headphoneAutoDetect
    val lastFmApiKey          = repository.lastFmApiKey

    private val _cacheMessage = MutableStateFlow<String?>(null)
    val cacheMessage = _cacheMessage.asStateFlow()
    private val _historyMessage = MutableStateFlow<String?>(null)
    val historyMessage = _historyMessage.asStateFlow()
    private val _customProfilesMessage = MutableStateFlow<String?>(null)
    val customProfilesMessage = _customProfilesMessage.asStateFlow()

    fun clearCustomProfiles() {
        viewModelScope.launch {
            try {
                val count = customProfiles.profiles.value.size
                if (repository.headphoneCorrectionSelection.first() is HeadphoneCorrectionSelection.Custom)
                    repository.selectNoCorrection()
                withContext(Dispatchers.IO) { customProfiles.clear() }
                _customProfilesMessage.value = "Cleared $count imported correction${if (count == 1) "" else "s"}."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _customProfilesMessage.value = "Could not clear imported corrections: ${e.message}" }
        }
    }

    fun dismissCustomProfilesMessage() { _customProfilesMessage.value = null }

    // ── Customised built-in curves (FEATURE_CUSTOM_GENRE_CURVES.md) ───

    val genreOverrides = repository.genreOverrides
    private val _genreCurvesMessage = MutableStateFlow<String?>(null)
    val genreCurvesMessage = _genreCurvesMessage.asStateFlow()

    fun resetGenreCurves() {
        viewModelScope.launch {
            try {
                val customised = repository.genreOverrides.first()
                repository.clearGenreOverrides()
                // Automatic mode follows the overrides flow; a manually chosen built-in is re-applied.
                val active = EqState.activePresetName.value
                if (EqState.manualOverrideActive.value && active in customised) {
                    EqProfileManager.defaultProfiles[active]?.let { EqState.applyManualPreset(active, it.copyOf()) }
                }
                val n = customised.size
                _genreCurvesMessage.value = "Reset $n genre curve${if (n == 1) "" else "s"} to default."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _genreCurvesMessage.value = "Could not reset genre curves: ${e.message}" }
        }
    }

    fun dismissGenreCurvesMessage() { _genreCurvesMessage.value = null }

    // ── Updates (FEATURES_NEXT_PLAN.md §1) ────────────────────────────

    val autoUpdateCheck = repository.autoUpdateCheck
    val updateStatus = UpdateChecker.status
    val latestRelease = UpdateChecker.latest

    fun setAutoUpdateCheck(enabled: Boolean) {
        viewModelScope.launch { repository.setAutoUpdateCheck(enabled) }
    }

    fun checkForUpdatesNow() {
        viewModelScope.launch { UpdateChecker.checkNow(repository) }
    }

    fun clearGenreHistory() {
        viewModelScope.launch {
            try {
                genreCoordinator.clearHistory()
                _historyMessage.value = "Genre observations and corrections cleared."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _historyMessage.value = "Could not clear genre history: ${e.message ?: "unknown error"}"
            }
        }
    }

    fun dismissHistoryMessage() { _historyMessage.value = null }

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

    fun setSmoothEqTransitions(enabled: Boolean) {
        viewModelScope.launch { repository.setSmoothEqTransitions(enabled) }
    }

    fun setOutputProtectionMode(mode: OutputProtectionMode) {
        // The processing service observes this preference and applies it immediately.
        viewModelScope.launch { repository.setOutputProtectionMode(mode) }
    }

    // ── Batch 2 (FEATURES_BATCH2_PLAN.md) ─────────────────────────────
    val volumeAwareBass = repository.volumeAwareBass
    val notificationBassButton = repository.notificationBassButton
    fun setVolumeAwareBass(enabled: Boolean) { viewModelScope.launch { repository.setVolumeAwareBass(enabled) } }
    fun setNotificationBassButton(enabled: Boolean) { viewModelScope.launch { repository.setNotificationBassButton(enabled) } }

    // ── Backup and restore (FEATURES_BATCH2_PLAN.md §3) ───────────────

    data class RestorePreview(val data: BackupData, val appVersion: String?, val createdAt: Long?)

    private val _restorePreview = MutableStateFlow<RestorePreview?>(null)
    val restorePreview = _restorePreview.asStateFlow()
    private val _backupMessage = MutableStateFlow<String?>(null)
    val backupMessage = _backupMessage.asStateFlow()
    private val _backupBusy = MutableStateFlow(false)
    val backupBusy = _backupBusy.asStateFlow()
    /** The settings before the last restore, for Undo in this session. */
    private val _undoSnapshot = MutableStateFlow<BackupData?>(null)
    val undoSnapshot = _undoSnapshot.asStateFlow()

    fun backupFileName(): String = BackupCodec.fileName(java.time.LocalDate.now().toString())

    private suspend fun currentBackupData(): BackupData =
        repository.exportBackupData().copy(importedCorrections = customProfiles.profiles.value)

    fun exportBackup(uri: android.net.Uri) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            _backupBusy.value = true
            try {
                val data = currentBackupData()
                val json = BackupCodec.build(data, BuildConfig.VERSION_NAME, System.currentTimeMillis())
                withContext(Dispatchers.IO) {
                    app.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                        ?: error("Could not open the chosen file.")
                }
                _undoSnapshot.value = null
                _backupMessage.value = "Backed up ${data.summary()}."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _backupMessage.value = "Could not save the backup: ${e.message}" }
            finally { _backupBusy.value = false }
        }
    }

    fun openBackup(uri: android.net.Uri) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            _backupBusy.value = true
            try {
                val doc = withContext(Dispatchers.IO) { BoundedTextReader.read(app, uri, BackupCodec.MAX_BYTES, "2 MB") }
                when (val result = BackupCodec.parse(doc.text)) {
                    is BackupCodec.Result.Error -> _backupMessage.value = result.message
                    is BackupCodec.Result.Ok ->
                        if (result.data.isEmpty) _backupMessage.value = "This backup is empty."
                        else _restorePreview.value = RestorePreview(result.data, result.appVersion, result.createdAt)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _backupMessage.value = "Could not read the backup: ${e.message}" }
            finally { _backupBusy.value = false }
        }
    }

    fun cancelRestore() { _restorePreview.value = null }

    fun confirmRestore(merge: Boolean) {
        val preview = _restorePreview.value ?: return
        viewModelScope.launch {
            _backupBusy.value = true
            try {
                val before = currentBackupData()
                val target = if (merge) BackupCodec.merge(before, preview.data) else preview.data
                applyBackup(target)
                _undoSnapshot.value = before
                _restorePreview.value = null
                _backupMessage.value = if (merge) "Added ${preview.data.summary()} from the backup."
                    else "Restored ${preview.data.summary()}."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _backupMessage.value = "Could not restore: ${e.message}" }
            finally { _backupBusy.value = false }
        }
    }

    fun undoRestore() {
        val before = _undoSnapshot.value ?: return
        viewModelScope.launch {
            try {
                applyBackup(before)
                _undoSnapshot.value = null
                _backupMessage.value = "Restore undone. Your previous settings are back."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _backupMessage.value = "Could not undo: ${e.message}" }
        }
    }

    fun dismissBackupMessage() { _backupMessage.value = null }

    /** One place that writes a backup and brings the running app in line with it. */
    private suspend fun applyBackup(data: BackupData) {
        withContext(Dispatchers.IO) { customProfiles.replaceAll(data.importedCorrections) }
        repository.applyBackupData(data)
        // Runtime state otherwise read only at startup (EqifyApplication.restorePersistedState).
        NowPlayingState.updateSelectedHeadphone(repository.selectedHeadphone.first())
        if (!repository.autoGenreDetection.first()) NowPlayingState.updateCurrentGenre("")
        val app = getApplication<Application>() as EqifyApplication
        val last = repository.lastPreset.first()
        EqState.restorePreset(last, app.loadPresetGains(repository, last))
    }

    // ── Batch 3 (FEATURES_BATCH3_PLAN.md) ─────────────────────────────
    val loudnessCompensation = repository.loudnessCompensation
    val volumeCapEnabled = repository.volumeCapEnabled
    val volumeCapLevel = repository.volumeCapLevel
    val automationEnabled = repository.automationEnabled
    fun setLoudnessCompensation(enabled: Boolean) { viewModelScope.launch { repository.setLoudnessCompensation(enabled) } }
    fun setVolumeCapEnabled(enabled: Boolean) { viewModelScope.launch { repository.setVolumeCapEnabled(enabled) } }
    fun setVolumeCapLevel(value: Float) { viewModelScope.launch { repository.setVolumeCapLevel(value) } }
    // ── Batch 4 (FEATURES_BATCH4_PLAN.md) ─────────────────────────────
    val balance = repository.balance
    val haptics = repository.haptics
    val deviceVolumeMemory = repository.deviceVolumeMemory
    val safeListeningEnabled = repository.safeListeningEnabled
    val safeListeningMode = repository.safeListeningMode
    val listeningDose = repository.listeningDose
    val limiterStatusForBalance = EqEngine.limiterDiagnosticStatus
    fun setBalance(value: Float) { viewModelScope.launch { repository.setBalance(value) } }
    fun setHaptics(enabled: Boolean) { viewModelScope.launch { repository.setHaptics(enabled) } }
    fun setDeviceVolumeMemory(enabled: Boolean) { viewModelScope.launch { repository.setDeviceVolumeMemory(enabled) } }
    fun setSafeListeningEnabled(enabled: Boolean) { viewModelScope.launch { repository.setSafeListeningEnabled(enabled) } }
    fun setSafeListeningMode(mode: SafeListening.Mode) { viewModelScope.launch { repository.setSafeListeningMode(mode) } }
    fun resetListeningDose() { viewModelScope.launch { repository.clearListeningDose() } }

    fun setAutomationEnabled(enabled: Boolean) { viewModelScope.launch { repository.setAutomationEnabled(enabled) } }

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

    fun clearDownloadedCorrections() {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val count = HeadphoneEqDiskCache.clear(app)
            app.startService(
                Intent(app, EqProcessingService::class.java).apply {
                    action = EqProcessingService.ACTION_CLEAR_HEADPHONE_CACHE
                }
            )
            _cacheMessage.value = when {
                count < 0 -> "Could not clear downloaded corrections."
                count == 0 -> "No downloaded corrections to clear."
                else -> "Cleared $count downloaded correction${if (count == 1) "" else "s"}."
            }
        }
    }

    fun dismissCacheMessage() {
        _cacheMessage.value = null
    }

    // ── Appearance (applies live; see ThemeStore) ─────────────────────

    val themeSettings = ThemeStore.settings

    fun setThemeMode(mode: ThemeMode) = ThemeStore.update(getApplication()) { it.copy(mode = mode) }

    /** Picking a fixed accent turns Dynamic colour off. */
    fun setThemeAccent(accent: ThemeAccent) =
        ThemeStore.update(getApplication()) { it.copy(accent = accent, dynamic = false) }

    fun setDynamicColor(enabled: Boolean) = ThemeStore.update(getApplication()) { it.copy(dynamic = enabled) }

    fun setPureBlack(enabled: Boolean) = ThemeStore.update(getApplication()) { it.copy(pureBlack = enabled) }

    // ── Crash reporting ───────────────────────────────────────────────

    val crashReportingConfigured = CrashReporting.isConfigured
    val isDebugBuild = BuildConfig.DEBUG
    private val _crashReportingEnabled = MutableStateFlow(CrashReporting.isOptedIn(application))
    val crashReportingEnabled = _crashReportingEnabled.asStateFlow()
    private val _crashReportMessage = MutableStateFlow<String?>(null)
    val crashReportMessage = _crashReportMessage.asStateFlow()

    fun setCrashReportingEnabled(enabled: Boolean) {
        CrashReporting.setOptedIn(getApplication(), enabled)
        _crashReportingEnabled.value = enabled
    }

    /** Debug builds only. Sends a handled test event and waits for it to upload. */
    fun sendTestCrashReport() {
        if (!BuildConfig.DEBUG) return
        viewModelScope.launch {
            val sent = withContext(Dispatchers.IO) { CrashReporting.sendTestEvent() }
            _crashReportMessage.value = if (sent) "Test error sent. Check the Sentry dashboard."
                else "Crash reporting is off or not configured in this build."
        }
    }

    /** Debug builds only. Deliberately crashes to verify uncaught-crash capture. */
    fun crashAppForTest() {
        if (!BuildConfig.DEBUG) return
        throw RuntimeException("EQify test crash (debug build)")
    }

    fun dismissCrashReportMessage() { _crashReportMessage.value = null }
}

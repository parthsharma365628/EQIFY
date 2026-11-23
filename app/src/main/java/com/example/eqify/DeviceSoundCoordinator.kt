package com.example.eqify

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

/**
 * Applies the connected device's own sound settings (FEATURES_NEXT_PLAN.md §4).
 *
 * Owns the effective bass ([EqState.bassBoostLevel]) and publishes the effective
 * genre mode, device preset and genre strength for the EQ service. A manual
 * change in this session still wins (the EQ paths handle that); connecting a
 * device that has its own Sound setting ends the manual change.
 */
object DeviceSoundCoordinator {
    private const val TAG = "DeviceSound"

    data class State(
        val deviceKey: String? = null,
        val kind: AudioOutputKind? = null,
        val label: String? = null,
        val profile: DeviceProfile? = null,
        val effective: EffectiveSound = DeviceProfiles.resolve(GlobalSound(true, 0f, GenreStrength.DEFAULT), null)
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private lateinit var repository: UserPreferencesRepository
    private var started = false

    fun start(repo: UserPreferencesRepository) {
        if (started) return
        started = true
        repository = repo

        // Remember every output we see, so it appears under My devices.
        scope.launch {
            HeadphoneDetector.output.filterNotNull().distinctUntilChangedBy { it.key }.collect { out ->
                try { repo.recordDeviceSeen(out.key, out.kind, out.displayName, System.currentTimeMillis()) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { Log.w(TAG, "Could not record device: ${e.message}") }
            }
        }

        scope.launch {
            combine(
                HeadphoneDetector.output,
                repo.deviceProfiles,
                repo.autoGenreDetection,
                repo.bassBoostLevel,
                repo.genreStrength
            ) { out, profiles, auto, bass, strength ->
                val profile = out?.let { profiles[it.key] }
                State(
                    deviceKey = out?.key,
                    kind = out?.kind,
                    label = profile?.label ?: out?.displayName,
                    profile = profile,
                    effective = DeviceProfiles.resolve(GlobalSound(auto, bass, strength), profile)
                )
            }.collect { next ->
                val previous = _state.value
                // A different device with its own Sound setting takes over from a manual change.
                if (previous.deviceKey != null && next.deviceKey != previous.deviceKey &&
                    (next.effective.soundFromDevice || previous.effective.soundFromDevice) &&
                    EqState.manualOverrideActive.value
                ) EqState.clearManualOverride()
                if (EqState.bassBoostLevel.value != next.effective.bassDb) EqState.setBassBoost(next.effective.bassDb)
                _state.value = next
            }
        }
    }

    /** Home's bass control: edits this device's bass if it has its own, else the default. */
    suspend fun setBass(level: Float) {
        val s = _state.value
        val value = DeviceProfiles.normaliseBass(level)
        EqState.setBassBoost(value)
        if (s.effective.bassFromDevice && s.deviceKey != null) {
            repository.updateDeviceProfile(s.deviceKey, s.kind ?: return) { it.copy(bassDb = value) }
        } else repository.setBassBoostLevel(value)
    }

    /** Home's strength control: same routing as [setBass]. */
    suspend fun setGenreStrength(value: Float) {
        val s = _state.value
        if (s.effective.strengthFromDevice && s.deviceKey != null) {
            repository.updateDeviceProfile(s.deviceKey, s.kind ?: return) {
                it.copy(genreStrength = GenreStrength.normalise(value))
            }
        } else repository.setGenreStrength(value)
    }
}

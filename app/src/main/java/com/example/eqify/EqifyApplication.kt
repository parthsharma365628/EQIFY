package com.example.eqify

import android.app.Application
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class EqifyApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val repository: UserPreferencesRepository by lazy {
        UserPreferencesRepository(this)
    }

    override fun onCreate() {
        super.onCreate()
        registerGlobalReceivers()
        restorePersistedState()
    }

    /**
     * Keep these on the [Application] — not [MainActivity]. When the user leaves EQify to play
     * music in Spotify / YT Music, the activity is often destroyed; registering only there meant
     * we stopped receiving [AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION] and
     * [MainActivity.onDestroy] even called [EqEngine.releaseEqualizer], tearing down the effect.
     */
    private fun registerGlobalReceivers() {
        try {
            ContextCompat.registerReceiver(
                this,
                EqEngine.sessionReceiver,
                EqEngine.getSessionIntentFilter(),
                ContextCompat.RECEIVER_EXPORTED
            )
            ContextCompat.registerReceiver(
                this,
                BluetoothHeadphoneDetector.receiver,
                BluetoothHeadphoneDetector.getIntentFilter(),
                ContextCompat.RECEIVER_EXPORTED
            )
            Log.d("EqifyApplication", "Global audio + Bluetooth receivers registered")
        } catch (e: Exception) {
            Log.e("EqifyApplication", "registerReceiver failed: ${e.message}")
        }
    }

    private fun restorePersistedState() {
        appScope.launch {
            val repo = repository
            repo.migrateLegacyCustomPresets()

            val eqEnabled = repo.isEqEnabled.first()
            EqState.setEnabled(eqEnabled)

            val headphone = repo.selectedHeadphone.first()
            NowPlayingState.updateSelectedHeadphone(headphone)

            val listenerEnabled = repo.mediaListenerEnabled.first()
            NowPlayingState.setMediaListenerEnabled(listenerEnabled)

            val bassBoost = repo.bassBoostLevel.first()
            EqState.setBassBoost(bassBoost)

            val lastPreset = repo.lastPreset.first()
            val presetGains = loadPresetGains(repo, lastPreset)
            EqState.restorePreset(lastPreset, presetGains)
        }
    }

    private suspend fun loadPresetGains(repo: UserPreferencesRepository, name: String): FloatArray {
        repo.loadCustomPresetGains(name)?.let { return it }
        EqProfileManager.defaultProfiles[name]?.let { return it.copyOf() }
        EqProfileManager.defaultProfiles.entries.find { it.key.equals(name, ignoreCase = true) }
            ?.value?.let { return it.copyOf() }
        return EqProfileManager.defaultProfiles["Flat"]!!.copyOf()
    }
}

package com.liteplane.eqify

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Detects which audio output is active and selects the matching headphone
 * correction (FEATURE_HEADPHONE_DETECTION.md). Replaces the former Bluetooth/
 * wired broadcast receiver.
 *
 * Uses AudioManager's device callback, so it sees only audio devices, both
 * connect and disconnect, Bluetooth, USB-C and 3.5 mm. All automatic actions
 * are gated by the "Headphone auto-detection" setting.
 */
object HeadphoneDetector {
    private const val TAG = "HeadphoneDetector"

    /** The current audio output. [key] identifies the device for remembered choices. */
    data class Output(val kind: AudioOutputKind, val productName: String?, val key: String) {
        val displayName: String get() = when (kind) {
            AudioOutputKind.WIRED -> "Wired headphones"
            AudioOutputKind.SPEAKER -> "Phone speaker"
            else -> productName?.takeIf { it.isNotBlank() } ?: when (kind) {
                AudioOutputKind.USB -> "USB headphones"
                else -> "Bluetooth headphones"
            }
        }
    }

    /** Something the Home screen should ask or tell about the current output. */
    sealed interface Prompt {
        val output: Output
        /** Selected automatically; offer Undo. */
        data class AutoSelected(override val output: Output, val model: String, val previous: HeadphoneCorrectionSelection) : Prompt
        /** Plausible model; ask before using it. */
        data class Suggest(override val output: Output, val model: String) : Prompt
        /** Nothing matched (or a wired/unnamed device): ask which headphones these are. */
        data class Unknown(override val output: Output) : Prompt
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val speaker = Output(AudioOutputKind.SPEAKER, null, "speaker")

    private val _output = MutableStateFlow<Output?>(null) // null until the first device scan
    val output: StateFlow<Output?> = _output.asStateFlow()

    private val _prompt = MutableStateFlow<Prompt?>(null)
    val prompt: StateFlow<Prompt?> = _prompt.asStateFlow()

    /**
     * True when the EQ must not apply a headphone correction: the phone speaker
     * is playing, or the user marked this device "Not headphones". Only while
     * auto-detection is on; otherwise the user's selection always applies.
     */
    private val _correctionSuppressed = MutableStateFlow(false)
    val correctionSuppressed: StateFlow<Boolean> = _correctionSuppressed.asStateFlow()

    private lateinit var appContext: Context
    private val repository get() = (appContext as EqifyApplication).repository

    /** Connected headphone-like outputs in connection order; the newest one is current. */
    private val connected = LinkedHashMap<Int, Output>()

    fun start(context: Context) {
        appContext = context.applicationContext
        scope.launch {
            combine(_output, repository.deviceChoices, repository.headphoneAutoDetect) { out, choices, auto ->
                auto && out != null && (out.kind == AudioOutputKind.SPEAKER || choices[out.key] == DeviceChoice.NotHeadphones)
            }.collect { _correctionSuppressed.value = it }
        }

        val audio = appContext.getSystemService(AudioManager::class.java) ?: return
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                addedDevices.filter { it.isSink }.forEach { info -> classify(info)?.let { connected[info.id] = it } }
                publish()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                removedDevices.forEach { connected.remove(it.id) }
                publish()
            }
        }
        // Called immediately with the devices that are already connected.
        audio.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
    }

    private fun classify(info: AudioDeviceInfo): Output? {
        val kind = when (info.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_HEARING_AID -> AudioOutputKind.BLUETOOTH
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE -> AudioOutputKind.USB
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> AudioOutputKind.WIRED
            else -> return null
        }
        val name = info.productName?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        return Output(kind, name, DeviceHeadphoneMemory.key(kind, name))
    }

    private fun publish() {
        val current = connected.values.lastOrNull() ?: speaker
        val previous = _output.value
        if (previous?.key == current.key && previous.kind == current.kind) return
        _output.value = current
        Log.d(TAG, "Audio output: ${current.kind} '${current.productName.orEmpty()}'")
        if (current.kind == AudioOutputKind.SPEAKER) {
            _prompt.value = null // the device the prompt was about is gone
            return
        }
        scope.launch {
            try { onNewOutput(current) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { Log.w(TAG, "Headphone detection failed: ${e.message}") }
        }
    }

    private suspend fun onNewOutput(out: Output) {
        if (!repository.headphoneAutoDetect.first()) return
        _prompt.value = null

        // 1. A choice the user made for this device before wins.
        when (val remembered = repository.deviceChoices.first()[out.key]) {
            is DeviceChoice.Dataset -> { applyDataset(remembered.name); return }
            is DeviceChoice.Custom -> {
                if ((appContext as EqifyApplication).customEqProfiles.find(remembered.profileId) != null) {
                    repository.selectCustomCorrection(remembered.profileId); return
                }
                // The imported correction was deleted: forget it and detect again.
                repository.setDeviceChoice(out.key, null)
            }
            DeviceChoice.NotHeadphones -> return // correctionSuppressed handles it
            null -> Unit
        }

        // 2. Wired and unnamed devices can't be matched: ask (then remember the answer).
        val name = out.productName
        if (out.kind == AudioOutputKind.WIRED || name.isNullOrBlank()) {
            _prompt.value = Prompt.Unknown(out)
            return
        }

        // 3. Named device: match against the cached catalog (short timeout; offline -> ask).
        val catalog = withTimeoutOrNull(5_000) {
            runCatching { HeadphoneDataRepository.allNames(appContext) }.getOrDefault(emptyList())
        }.orEmpty()
        if (_output.value?.key != out.key) return // device changed while matching
        when (val match = HeadphoneMatcher.match(name, catalog)) {
            is HeadphoneMatcher.Result.Confident -> {
                val before = repository.headphoneCorrectionSelection.first()
                if (before == HeadphoneCorrectionSelection.Dataset(match.model)) return // already right
                applyDataset(match.model)
                _prompt.value = Prompt.AutoSelected(out, match.model, before)
            }
            is HeadphoneMatcher.Result.Likely -> _prompt.value = Prompt.Suggest(out, match.model)
            HeadphoneMatcher.Result.None -> _prompt.value = Prompt.Unknown(out)
        }
    }

    private suspend fun applyDataset(name: String) {
        NowPlayingState.updateSelectedHeadphone(name)
        repository.setSelectedHeadphone(name)
    }

    // ── Actions from the UI ───────────────────────────────────────────

    fun dismissPrompt() { _prompt.value = null }

    /** Undo an automatic selection: restore what was selected before. Nothing is remembered. */
    fun undo(prompt: Prompt.AutoSelected) {
        _prompt.value = null
        scope.launch {
            when (val p = prompt.previous) {
                is HeadphoneCorrectionSelection.Dataset -> applyDataset(p.name)
                is HeadphoneCorrectionSelection.Custom -> repository.selectCustomCorrection(p.profileId)
                HeadphoneCorrectionSelection.None -> repository.selectNoCorrection()
            }
        }
    }

    /** Accept a suggested model for this device, and remember it. */
    fun useSuggestion(prompt: Prompt.Suggest) {
        _prompt.value = null
        scope.launch {
            applyDataset(prompt.model)
            repository.setDeviceChoice(prompt.output.key, DeviceChoice.Dataset(prompt.model))
        }
    }

    /** This device isn't headphones (car, speaker…): never apply a headphone correction on it. */
    fun markNotHeadphones(output: Output) {
        _prompt.value = null
        scope.launch { repository.setDeviceChoice(output.key, DeviceChoice.NotHeadphones) }
    }

    /**
     * Called when the user explicitly picks a correction (Headphones screen).
     * Remembers it for the current device, if one is connected.
     */
    fun rememberForCurrentOutput(choice: DeviceChoice) {
        val out = _output.value ?: return
        if (out.kind == AudioOutputKind.SPEAKER) return
        _prompt.value = null
        scope.launch { repository.setDeviceChoice(out.key, choice) }
    }
}

package com.liteplane.eqify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioFocusRequest
import android.widget.Toast
import kotlinx.coroutines.Job
import androidx.core.content.ContextCompat
import android.media.AudioManager
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class EqProcessingService : Service() {

    companion object {
        private const val TAG = "EqProcessingService"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID      = "eqify_eq_channel"
        const val ACTION_START    = "com.liteplane.eqify.ACTION_START"
        const val ACTION_STOP     = "com.liteplane.eqify.ACTION_STOP"
        const val ACTION_TOGGLE   = "com.liteplane.eqify.ACTION_TOGGLE"
        const val ACTION_SET_ENABLED = "com.liteplane.eqify.ACTION_SET_ENABLED"
        const val EXTRA_ENABLED = "enabled"
        const val ACTION_CLEAR_HEADPHONE_CACHE =
            "com.liteplane.eqify.ACTION_CLEAR_HEADPHONE_CACHE"
        const val ACTION_CYCLE_BASS = "com.liteplane.eqify.ACTION_CYCLE_BASS"
        // Batch 3 (FEATURES_BATCH3_PLAN.md): shared by shortcuts, automation and the widget.
        const val ACTION_SET_BASS = "com.liteplane.eqify.ACTION_SET_BASS"
        const val ACTION_SET_PRESET = "com.liteplane.eqify.ACTION_SET_PRESET"
        const val ACTION_SET_AUTO_GENRE = "com.liteplane.eqify.ACTION_SET_AUTO_GENRE"
        const val ACTION_SLEEP_START = "com.liteplane.eqify.ACTION_SLEEP_START"
        const val ACTION_SLEEP_CANCEL = "com.liteplane.eqify.ACTION_SLEEP_CANCEL"
        // Safe-listening alert actions (FEATURES_BATCH4_PLAN.md L).
        const val ACTION_LOWER_VOLUME = "com.liteplane.eqify.ACTION_LOWER_VOLUME"
        const val ACTION_ENABLE_VOLUME_LIMIT = "com.liteplane.eqify.ACTION_ENABLE_VOLUME_LIMIT"
        const val EXTRA_LEVEL = "level"
        const val EXTRA_NAME = "name"
        const val EXTRA_MINUTES = "minutes"
        private const val LIMIT_OUTPUT_GAIN_DB = 6f
        private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
        private const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val headphoneEqCache = mutableMapOf<String, FloatArray>()
    private var serviceActive = false
    private var lastAutoSnapshot: AutoSnapshot? = null
    private var lastNotificationText = "EQify starting…"
    private var lastLoggedTone: String? = null
    private var showBassButton = true

    // Volume-aware bass: follow media volume (FEATURES_BATCH2_PLAN.md §1).
    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val stream = intent?.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, AudioManager.STREAM_MUSIC)
            if (stream == AudioManager.STREAM_MUSIC) refreshMediaVolume()
        }
    }
    private var volumeReceiverRegistered = false
    // Volume cap (FEATURES_BATCH3_PLAN.md §A).
    private var volumeCapEnabled = false
    private var volumeCapLevel = VolumeCap.DEFAULT
    private var capToastShown = false
    // Sleep timer (FEATURES_BATCH3_PLAN.md §C).
    private var sleepJob: Job? = null
    /** Volume before the fade started; non-null only while a fade runs. */
    private var fadeOriginalVolume: Int? = null
    private val gainRamp = EqGainRampController(
        scope = serviceScope,
        clockMs = { SystemClock.uptimeMillis() },
        canWrite = {
            serviceActive && EqState.isEqEnabled.value && !EqState.isBypassed.value &&
                !EqState.manualOverrideActive.value
        },
        write = EqEngine::writeRampStep
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        serviceActive = true
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("EQify starting…"))

        // Attach equalizer immediately; retry a few times if not ready yet
        serviceScope.launch {
            repeat(5) { attempt ->
                if (EqEngine.equalizer != null) return@repeat
                EqEngine.initSessionZero()
                if (EqEngine.equalizer != null) {
                    Log.d(TAG, "Equalizer ready on attempt ${attempt + 1}")
                    return@repeat
                }
                delay((attempt + 1) * 500L)
            }
            if (EqEngine.equalizer == null) {
                Log.w(TAG, "Equalizer not available — waiting for audio session broadcast")
            }
        }

        observeAutoEqPipeline()     // PATH A: genre/preset driven (may hit network)
        observeManualAdjustments()  // PATH B: slider driven (instant, no network)
        EqState.setServiceRunning(true)
        observeProcessingSnapshot()
        if (BuildConfig.DEBUG) {
            val audio = getSystemService(AudioManager::class.java)
            if (audio != null) serviceScope.launch {
                OrbitPrototype.observe(audio, (application as EqifyApplication).repository)
            }
        }
        observeBatch2Settings()
        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP   -> stopSelf()
            ACTION_TOGGLE -> {
                val enabled = !EqState.isEqEnabled.value
                EqState.setEnabled(enabled)
                if (enabled && EqEngine.equalizer == null) EqEngine.initSessionZero()
                serviceScope.launch {
                    (application as EqifyApplication).repository.setEqEnabled(enabled)
                }
            }
            ACTION_SET_ENABLED -> {
                val enabled = intent.getBooleanExtra(EXTRA_ENABLED, true)
                EqState.setEnabled(enabled)
                if (enabled && EqEngine.equalizer == null) EqEngine.initSessionZero()
                serviceScope.launch {
                    (application as EqifyApplication).repository.setEqEnabled(enabled)
                }
            }
            ACTION_SET_BASS -> serviceScope.launch {
                val level = intent.getFloatExtra(EXTRA_LEVEL, -1f)
                if (level.isFinite() && level in 0f..12f) DeviceSoundCoordinator.setBass(level)
            }
            ACTION_SET_PRESET -> intent.getStringExtra(EXTRA_NAME)?.let { name -> serviceScope.launch { applyPresetByName(name) } }
            ACTION_SET_AUTO_GENRE -> serviceScope.launch {
                val enabled = intent.getBooleanExtra(EXTRA_ENABLED, true)
                (application as EqifyApplication).repository.setAutoGenreDetection(enabled)
                if (!enabled) NowPlayingState.updateCurrentGenre("")
            }
            ACTION_SLEEP_START -> startSleepTimer(intent.getIntExtra(EXTRA_MINUTES, 0))
            ACTION_SLEEP_CANCEL -> cancelSleepTimer()
            ACTION_LOWER_VOLUME -> {
                dismissSafeListeningAlert()
                if (fadeOriginalVolume == null) {
                    val audio = getSystemService(AUDIO_SERVICE) as AudioManager
                    runCatching {
                        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        val target = max / 2
                        if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) > target)
                            audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
                    }
                    refreshMediaVolume()
                }
            }
            ACTION_ENABLE_VOLUME_LIMIT -> {
                dismissSafeListeningAlert()
                serviceScope.launch {
                    val repository = (application as EqifyApplication).repository
                    repository.setVolumeCapLevel(VolumeCap.DEFAULT)
                    repository.setVolumeCapEnabled(true)
                }
            }
            ACTION_CYCLE_BASS -> serviceScope.launch {
                // Through the coordinator, so a device's own bass setting is edited (AI.md).
                DeviceSoundCoordinator.setBass(NotificationControls.nextBass(EqState.bassBoostLevel.value))
            }
            ACTION_CLEAR_HEADPHONE_CACHE -> {
                headphoneEqCache.clear()
                EqState.updateHeadphoneCorrectionStatus(HeadphoneCorrectionStatus.Idle)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceActive = false
        gainRamp.cancel()
        if (BuildConfig.DEBUG) OrbitPrototype.stop()
        if (EqState.isEqEnabled.value && !EqState.isBypassed.value) {
            lastAutoSnapshot?.let { EqEngine.applyGains(it.outputGains) }
        }
        setMonoOutput(false)
        cancelSleepTimer()
        if (volumeReceiverRegistered) runCatching { unregisterReceiver(volumeReceiver) }
        volumeReceiverRegistered = false
        serviceScope.cancel()
        EqState.setServiceRunning(false)
        ExternalStatus.refresh(this)
        Log.d(TAG, "Service destroyed")
    }

    /**
     * Persists only the small, user-visible processing state. External controls can read this
     * without polling the audio engine or relying on process-local state for the profile name.
     */
    private fun observeProcessingSnapshot() {
        val repository = (application as EqifyApplication).repository
        serviceScope.launch {
            combine(
                EqState.isEqEnabled,
                EqState.isBypassed,
                EqState.activePresetName,
                EqEngine.isEqualizerAttached
            ) { enabled, bypassed, activeProfile, attached ->
                val status = when {
                    !enabled -> EqProcessingStatus.PAUSED
                    bypassed -> EqProcessingStatus.BYPASSED
                    attached -> EqProcessingStatus.ACTIVE
                    else -> EqProcessingStatus.UNAVAILABLE
                }
                status to activeProfile
            }.distinctUntilChanged().collect { (status, activeProfile) ->
                repository.setProcessingSnapshot(status, activeProfile)
                ExternalStatus.refresh(this@EqProcessingService)
            }
        }
    }

    /**
     * The one media-volume reader (FEATURES_BATCH3_PLAN.md §1): clamps to the volume
     * cap, then feeds volume-aware bass and loudness compensation through EqState.
     */
    private fun refreshMediaVolume() {
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        val fraction = runCatching {
            var volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (volumeCapEnabled && fadeOriginalVolume == null) {
                VolumeCap.clampIndex(volume, max, volumeCapLevel)?.let { capped ->
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, capped, 0)
                    volume = capped
                    clampedJustNow = true
                    ChangeLog.add("Volume limited to ${Math.round(volumeCapLevel * 100)}%")
                    if (!capToastShown) {
                        capToastShown = true
                        Toast.makeText(this, "EQify volume limit is on (${Math.round(volumeCapLevel * 100)}%)",
                            Toast.LENGTH_SHORT).show()
                    }
                }
            }
            VolumeBass.fraction(volume, max)
        }.getOrDefault(0f)
        EqState.setMediaVolumeFraction(fraction)
        rememberDeviceVolume(fraction)
    }

    // ── Per-device volume memory (FEATURES_BATCH4_PLAN.md S7) ─────────
    private var deviceVolumeMemory = false
    private var clampedJustNow = false
    private var restoringUntil = 0L

    /** Saves the user's own volume for the current output: not during a fade, a clamp or a restore. */
    private fun rememberDeviceVolume(fraction: Float) {
        val wasClamp = clampedJustNow
        clampedJustNow = false
        if (!deviceVolumeMemory || wasClamp || fadeOriginalVolume != null) return
        if (SystemClock.elapsedRealtime() < restoringUntil) return
        val key = HeadphoneDetector.output.value?.key ?: return
        val repository = (application as EqifyApplication).repository
        serviceScope.launch { repository.setDeviceVolume(key, fraction) }
    }

    private suspend fun restoreDeviceVolume(key: String, label: String) {
        if (!deviceVolumeMemory || fadeOriginalVolume != null) return
        val saved = (application as EqifyApplication).repository.deviceVolumes.first()[key] ?: return
        // Android may set its own per-device volume first; let it settle.
        restoringUntil = SystemClock.elapsedRealtime() + 2_500
        delay(1_000)
        if (fadeOriginalVolume != null || HeadphoneDetector.output.value?.key != key) return
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        runCatching {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val index = VolumeCap.limit(DeviceVolumes.toIndex(saved, max), max, volumeCapEnabled, volumeCapLevel)
            if (index != audio.getStreamVolume(AudioManager.STREAM_MUSIC)) {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
                ChangeLog.add("Volume set to ${Math.round(index * 100f / max)}% for $label")
            }
        }
        refreshMediaVolume()
    }

    // ── Safe listening (FEATURES_BATCH4_PLAN.md, research) ────────────
    private var safeListeningJob: kotlinx.coroutines.Job? = null

    private fun setSafeListening(enabled: Boolean) {
        if (!enabled) { safeListeningJob?.cancel(); safeListeningJob = null; return }
        if (safeListeningJob != null) return
        val repository = (application as EqifyApplication).repository
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        safeListeningJob = serviceScope.launch {
            var pending = 0.0
            var lastFlush = SystemClock.elapsedRealtime()
            while (true) {
                delay(1_000)
                val out = HeadphoneDetector.output.value
                val onHeadphones = out != null && out.kind != AudioOutputKind.SPEAKER &&
                    !HeadphoneDetector.correctionSuppressed.value
                if (onHeadphones && runCatching { audio.isMusicActive }.getOrDefault(false)) {
                    val mode = repository.safeListeningMode.first()
                    val level = SafeListening.estimatedLevelDb(EqState.mediaVolumeFraction.value,
                        if (EqState.isEqEnabled.value && !EqState.isBypassed.value) EqState.activeBandGains.value else FloatArray(8))
                    pending += SafeListening.doseFor(level, 1.0, mode)
                }
                val now = SystemClock.elapsedRealtime()
                if (pending > 0.0 && now - lastFlush >= 60_000) {
                    val weekly = repository.addListeningDose(java.time.LocalDate.now(), pending)
                    pending = 0.0
                    lastFlush = now
                    SafeListening.alertStep(weekly, repository.safeListeningAlerted.first())?.let { step ->
                        repository.setSafeListeningAlerted(step)
                        ChangeLog.add("Safe listening: ${SafeListening.percent(weekly)}% of this week's allowance")
                        SafeListeningAlerts.notify(this@EqProcessingService, SafeListening.percent(weekly))
                    }
                }
            }
        }
    }

    private fun setVolumeMonitor(needed: Boolean) {
        if (needed && !volumeReceiverRegistered) {
            ContextCompat.registerReceiver(this, volumeReceiver,
                IntentFilter(VOLUME_CHANGED_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
            volumeReceiverRegistered = true
        } else if (!needed && volumeReceiverRegistered) {
            runCatching { unregisterReceiver(volumeReceiver) }
            volumeReceiverRegistered = false
        }
    }

    private fun observeBatch2Settings() {
        val repository = (application as EqifyApplication).repository
        serviceScope.launch {
            combine(
                combine(repository.volumeAwareBass, repository.loudnessCompensation,
                    repository.volumeCapEnabled, repository.volumeCapLevel) { a, b, c, d -> listOf(a, b, c, d) },
                combine(repository.safeListeningEnabled, repository.deviceVolumeMemory) { a, b -> a to b }
            ) { first, second -> first to second }.collect { (first, second) ->
                val (bass, loud, capOn, cap) = first
                val (safe, devVol) = second
                volumeCapEnabled = capOn as Boolean
                volumeCapLevel = cap as Float
                deviceVolumeMemory = devVol
                setSafeListening(safe)
                setVolumeMonitor(bass as Boolean || loud as Boolean || capOn || safe || devVol)
                refreshMediaVolume()
                EqState.setVolumeAwareBass(bass)
                EqState.setLoudnessCompensation(loud as Boolean)
            }
        }
        serviceScope.launch {
            SleepTimer.state.collect {
                updateNotification(lastNotificationText)
                ExternalStatus.refresh(this@EqProcessingService)
            }
        }
        // Volume is per output: re-read it when the headphones change.
        serviceScope.launch {
            var previousKey: String? = null
            HeadphoneDetector.output.distinctUntilChanged { a, b -> a?.key == b?.key }.collectLatest { out ->
                refreshMediaVolume()
                val key = out?.key
                if (key != previousKey) {
                    if (out != null) ChangeLog.add("Now playing on ${out.displayName}")
                    else if (previousKey != null) ChangeLog.add("Output disconnected")
                    previousKey = key
                    if (out != null) restoreDeviceVolume(out.key, out.displayName)
                }
            }
        }
        // Stereo balance (FEATURES_BATCH4_PLAN.md S2).
        serviceScope.launch {
            repository.balance.collect { b ->
                val (l, r) = StereoBalance.channelGains(b)
                EqEngine.setBalance(l, r)
            }
        }
        serviceScope.launch {
            repository.notificationBassButton.collect { show ->
                showBassButton = show
                updateNotification(lastNotificationText)
            }
        }
        // The bass button label follows the chosen bass level.
        serviceScope.launch {
            EqState.bassBoostLevel.collect {
                updateNotification(lastNotificationText)
                ExternalStatus.refresh(this@EqProcessingService)
            }
        }
    }

    private fun dismissSafeListeningAlert() {
        runCatching { getSystemService(NotificationManager::class.java).cancel(1002) }
    }

    // ── Commands from shortcuts, automation and the widget ────────────

    /** Same steps as choosing the preset on the EQ screen. Unknown names are ignored. */
    private suspend fun applyPresetByName(name: String) {
        val repository = (application as EqifyApplication).repository
        val custom = repository.customPresetNames.first().firstOrNull { it.equals(name, ignoreCase = true) }
        val builtIn = EqProfileManager.defaultProfiles.keys.firstOrNull { it.equals(name, ignoreCase = true) }
        val resolved = custom ?: builtIn ?: return
        val gains = (if (custom != null) repository.loadCustomPresetGains(custom) else null)
            ?: EqProfileManager.effectiveGains(resolved, repository.genreOverrides.first())
            ?: return
        EqState.applyManualPreset(resolved, gains)
        repository.setLastPreset(resolved)
    }

    // ── Sleep timer (FEATURES_BATCH3_PLAN.md §C) ──────────────────────

    private fun startSleepTimer(minutes: Int) {
        if (!SleepTimer.isValidRequest(minutes)) return
        cancelSleepTimer()
        if (minutes == 0) return
        sleepJob = serviceScope.launch {
            if (minutes == SleepTimer.END_OF_TRACK) {
                SleepTimer.publish(SleepTimer.State.UntilTrackEnds)
                val current = NowPlayingState.observation.value
                NowPlayingState.observation.first { it != null && it != current }
                fadeAndPause(SleepTimer.SHORT_FADE_MS)
            } else {
                val endsAt = SystemClock.elapsedRealtime() + minutes * 60_000L
                while (true) {
                    SleepTimer.publish(SleepTimer.State.Counting(endsAt, minutes))
                    val untilFade = endsAt - SleepTimer.FADE_MS - SystemClock.elapsedRealtime()
                    if (untilFade <= 0) break
                    delay(minOf(untilFade, 60_000L))
                }
                fadeAndPause(SleepTimer.FADE_MS)
            }
        }
    }

    private fun cancelSleepTimer() {
        sleepJob?.cancel()
        sleepJob = null
        restoreAfterFade()
        SleepTimer.publish(SleepTimer.State.Off)
    }

    /** Puts the volume back after a cancelled fade, never above the volume cap. */
    private fun restoreAfterFade() {
        val original = fadeOriginalVolume ?: return
        fadeOriginalVolume = null
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        runCatching {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC,
                VolumeCap.limit(original, max, volumeCapEnabled, volumeCapLevel), 0)
        }
        EqState.setVolumeFeaturesFrozen(false)
        refreshMediaVolume()
    }

    private suspend fun fadeAndPause(durationMs: Long) {
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        val original = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        SleepTimer.publish(SleepTimer.State.Fading)
        ChangeLog.add("Sleep timer: fading out")
        // Loudness and bass easing hold still, so the fade never adds bass (plan §1).
        EqState.setVolumeFeaturesFrozen(true)
        fadeOriginalVolume = original
        var lastSet = original
        for ((gap, index) in SleepTimer.fadeSteps(original, durationMs)) {
            delay(gap)
            if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) != lastSet) {
                // The user changed the volume: they're awake. Stop the timer, keep their volume.
                fadeOriginalVolume = null
                EqState.setVolumeFeaturesFrozen(false)
                refreshMediaVolume()
                SleepTimer.publish(SleepTimer.State.Off)
                sleepJob = null
                return
            }
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
            lastSet = index
        }
        // Asking for audio focus makes well-behaved music apps pause.
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .build()
        runCatching { audio.requestAudioFocus(focus) }
        delay(1_500)
        runCatching { audio.abandonAudioFocusRequest(focus) }
        if (audio.isMusicActive) {
            // Restoring now would blast the music, so leave it at 0 and say why.
            fadeOriginalVolume = null
            EqState.setVolumeFeaturesFrozen(false)
            refreshMediaVolume()
            SleepTimer.publish(SleepTimer.State.PlayerKeptPlaying)
        } else {
            restoreAfterFade()
            SleepTimer.publish(SleepTimer.State.Off)
        }
        sleepJob = null
    }

    // ── PATH A: Auto-EQ pipeline ──────────────────────────────────────
    // Fires when: genre changes, headphone changes, preset changes,
    // bass boost changes, EQ toggle changes.
    // May call resolveHeadphoneCorrection which can make a network call.
    // collectLatest cancels the previous run when new values arrive,
    // which is fine here because slider moves go through PATH B instead.

    private fun observeAutoEqPipeline() {
        val repository = (application as EqifyApplication).repository

        serviceScope.launch {
            combine(
                combine(
                    NowPlayingState.currentGenre,
                    NowPlayingState.selectedHeadphone,
                    EqState.isEqEnabled,
                    EqState.isBypassed,
                    repository.headphoneCorrectionSelection
                ) { g, h, e, bypassed, correction -> SourceState(g, h, e, bypassed, correction) },
                combine(
                    repository.outputProtectionMode,
                    repository.forceMono,
                    // Bass and strength are the connected device's effective values (FEATURES_NEXT_PLAN.md §4).
                    // Applied bass: eased at high volume when volume-aware bass is on (FEATURES_BATCH2_PLAN.md §1).
                    // Loudness compensation is the next layer, before protection (FEATURES_BATCH3_PLAN.md §2).
                    combine(EqState.appliedBassBoost,
                        DeviceSoundCoordinator.state.map { it.effective.genreStrength }.distinctUntilChanged(),
                        EqState.appliedLoudness,
                        EqState.levelMatchActive
                    ) { b, s, loud, match -> Triple(b, s, loud) to match },
                    repository.smoothEqTransitions,
                    HeadphoneDetector.correctionSuppressed
                ) { l, m, bsm, smooth, noHp ->
                    val bs = bsm.first
                    AudioSettingsState(l, m, bs.first, smooth, noHp, bs.second, bs.third, bsm.second)
                },
                combine(
                    DeviceSoundCoordinator.state.map { it.effective.autoGenre to it.effective.devicePreset }
                        .distinctUntilChanged(),
                    EqState.baseToneGains,
                    EqState.tonePresetName,
                    EqState.manualOverrideActive,
                    combine(repository.customPresetGains, repository.genreOverrides) { presets, overrides ->
                        presets to overrides
                    }
                ) { mode, bt, tn, manual, library ->
                    AutoToneState(mode.first, bt, tn, manual, library.first, library.second, mode.second)
                }
            ) { t1, t2, t3 ->
                EqPipe(
                    genre         = t1.genre,
                    headphone     = t1.headphone,
                    correctionSelection = t1.correctionSelection,
                    enabled       = t1.enabled,
                    bypassed      = t1.bypassed,
                    outputProtectionMode = t2.outputProtectionMode,
                    mono          = t2.mono,
                    bassBoost     = t2.bassBoost,
                    smoothTransitions = t2.smoothTransitions,
                    correctionSuppressed = t2.correctionSuppressed,
                    genreStrength = t2.genreStrength,
                    loudness      = t2.loudness,
                    levelMatch    = t2.levelMatch,
                    autoGenre     = t3.autoGenre,
                    baseToneGains = t3.baseToneGains,
                    toneName      = t3.toneName,
                    manualOverride = t3.manualOverride,
                    customPresets = t3.customPresets,
                    genreOverrides = t3.genreOverrides,
                    devicePreset  = t3.devicePreset
                )
            }.collectLatest { pipe ->
                val previous = lastAutoSnapshot
                val protectionChanged = previous != null &&
                    previous.outputProtectionMode != pipe.outputProtectionMode
                if (!pipe.enabled || pipe.bypassed || !pipe.smoothTransitions ||
                    protectionChanged
                ) gainRamp.cancel()
                EqState.setOutputProtectionMode(pipe.outputProtectionMode)
                setMonoOutput(pipe.mono)
                if (EqEngine.equalizer == null) EqEngine.initSessionZero()
                EqEngine.setLimiterProtectionEnabled(
                    pipe.enabled && !pipe.bypassed &&
                        pipe.outputProtectionMode == OutputProtectionMode.BALANCED
                )
                val limiterState = EqEngine.limiterDiagnosticStatus.value.state
                if (pipe.outputProtectionMode == OutputProtectionMode.BALANCED &&
                    (limiterState == LimiterDiagnosticState.FAILED ||
                        limiterState == LimiterDiagnosticState.UNSUPPORTED)
                ) {
                    Log.w(TAG, "Balanced protection unavailable; switching to Safe")
                    ChangeLog.add("Output protection switched to Safe (limiter unavailable)")
                    repository.setOutputProtectionMode(OutputProtectionMode.SAFE)
                }

                if (!pipe.enabled || pipe.bypassed) {
                    gainRamp.cancel()
                    EqEngine.applyFlat()
                    lastAutoSnapshot = null
                    EqState.updateOutputGains(FloatArray(8))
                    updateNotification(if (pipe.bypassed) "EQ bypassed for comparison" else "EQ paused")
                    return@collectLatest
                }

                // A Safe transition must not wait behind a headphone lookup while
                // an old Off/Balanced ramp remains above Safe's boost limit.
                if (previous != null && protectionChanged) {
                    val safeNow = pipe.outputProtectionMode == OutputProtectionMode.SAFE ||
                        (pipe.outputProtectionMode == OutputProtectionMode.BALANCED &&
                            !EqEngine.isLimiterProtectionActive())
                    EqEngine.applyGains(if (safeNow) applyLimitOutputGain(previous.outputGains)
                        else previous.outputGains)
                } else if (previous != null && previous.smoothTransitions &&
                    !pipe.smoothTransitions) {
                    EqEngine.applyGains(previous.outputGains)
                }

                // Fetch headphone correction (cached after first fetch — no repeated calls).
                // Not on headphones (phone speaker / "Not headphones" device): none at all; the
                // saved selection is kept, so reconnecting restores it (FEATURE_HEADPHONE_DETECTION.md).
                val hpCorrection = if (pipe.correctionSuppressed) {
                    EqState.updateHeadphoneCorrectionStatus(HeadphoneCorrectionStatus.NotOnHeadphones(
                        HeadphoneDetector.output.value?.displayName ?: "Phone speaker"))
                    FloatArray(8)
                } else when (val selection = pipe.correctionSelection) {
                    is HeadphoneCorrectionSelection.Dataset -> resolveHeadphoneCorrection(selection.name)
                    is HeadphoneCorrectionSelection.Custom -> {
                        val profile = (application as EqifyApplication).customEqProfiles.find(selection.profileId)
                        if (profile == null) {
                            EqState.updateHeadphoneCorrectionStatus(
                                HeadphoneCorrectionStatus.ImportFailed("The imported correction is missing or unreadable, so no headphone correction is applied.")
                            )
                            FloatArray(8)
                        } else {
                            EqState.updateHeadphoneCorrectionStatus(HeadphoneCorrectionStatus.Imported(profile.displayName))
                            profile.gains.toFloatArray()
                        }
                    }
                    HeadphoneCorrectionSelection.None -> {
                        EqState.updateHeadphoneCorrectionStatus(HeadphoneCorrectionStatus.Idle)
                        FloatArray(8)
                    }
                }
                EqState.updateHeadphoneCorrection(hpCorrection)

                // A song corrected to one of the user's presets uses that preset's
                // curve; a deleted/unknown preset falls back to Flat.
                val correctedPreset = GenreChoice.presetName(pipe.genre)
                val tonePart: FloatArray = when {
                    pipe.manualOverride ->
                        pipe.baseToneGains.copyOf()
                    // This device always uses a preset: user preset, customised built-in, built-in, else Flat.
                    pipe.devicePreset != null ->
                        pipe.customPresets[pipe.devicePreset]?.toFloatArray()
                            ?: EqProfileManager.effectiveGains(pipe.devicePreset, pipe.genreOverrides)
                            ?: EqProfileManager.defaultProfiles["Flat"]!!.copyOf()
                    pipe.autoGenre && correctedPreset != null ->
                        pipe.customPresets[correctedPreset]?.toFloatArray()
                            ?: EqProfileManager.defaultProfiles["Flat"]!!.copyOf()
                    pipe.autoGenre && pipe.genre.isNotBlank() ->
                        EqProfileManager.resolveGenreGains(pipe.genre, pipe.genreOverrides)
                    pipe.autoGenre ->
                        EqProfileManager.defaultProfiles["Flat"]!!.copyOf()
                    else ->
                        pipe.baseToneGains.copyOf()
                }

                // Genre strength scales only the automatic tone (never a manual curve).
                // Sliders keep showing the curve itself; only the output is scaled.
                val appliedTone = if (pipe.manualOverride || !pipe.autoGenre || pipe.devicePreset != null) tonePart
                    else GenreStrength.apply(tonePart, pipe.genreStrength)
                var combined = LoudnessCompensation.add(
                    EqProfileManager.combineGains(appliedTone, hpCorrection, pipe.bassBoost),
                    pipe.loudness
                )
                // A/B level match: only while the EQ screen compares (FEATURES_BATCH4_PLAN.md S1).
                if (pipe.levelMatch) combined = LevelMatch.apply(combined, LevelMatch.offset(combined))

                val useSafeProtection =
                    pipe.outputProtectionMode == OutputProtectionMode.SAFE ||
                        (pipe.outputProtectionMode == OutputProtectionMode.BALANCED &&
                            !EqEngine.isLimiterProtectionActive())
                if (useSafeProtection) {
                    combined = applyLimitOutputGain(combined)
                }

                val displayName = when {
                    pipe.manualOverride -> pipe.toneName.ifBlank { "Custom" }
                    pipe.devicePreset != null ->
                        if (pipe.customPresets.containsKey(pipe.devicePreset) ||
                            EqProfileManager.defaultProfiles.containsKey(pipe.devicePreset)) pipe.devicePreset else "Flat"
                    pipe.autoGenre && correctedPreset != null ->
                        if (pipe.customPresets.containsKey(correctedPreset)) correctedPreset else "Flat"
                    pipe.autoGenre && pipe.genre.isNotBlank() ->
                        EqProfileManager.displayNameForGenre(pipe.genre)
                    pipe.autoGenre -> "Flat"
                    else -> pipe.toneName.ifBlank { "Flat" }
                }
                val bassTag = if (pipe.bassBoost > 0f) "bass +${pipe.bassBoost.toInt()} dB" else ""

                // A combine emission can contain an older manual curve. Never
                // publish it over a newer slider edit, even after a cache hit.
                if (pipe.manualOverride != EqState.manualOverrideActive.value ||
                    (EqState.manualOverrideActive.value &&
                        !pipe.baseToneGains.contentEquals(EqState.baseToneGains.value))
                ) return@collectLatest
                EqState.applyComputedOutput(displayName, tonePart, combined)
                val shouldRamp = pipe.smoothTransitions && !pipe.manualOverride &&
                    previous != null && previous.canRampTo(pipe, hpCorrection, combined)
                val nextSnapshot = AutoSnapshot.from(pipe, hpCorrection, combined)
                if (shouldRamp) {
                    gainRamp.cancel()
                    val prepared = EqEngine.prepareGainRamp(combined)
                    if (prepared != null) gainRamp.start(prepared)
                } else if (previous == null || !previous.outputGains.contentEquals(combined) ||
                    previous.requiresSnap(pipe)
                ) {
                    gainRamp.cancel()
                    EqEngine.applyGains(combined)
                }
                lastAutoSnapshot = nextSnapshot

                val hpShort = pipe.headphone.split(" ").takeLast(2).joinToString(" ")
                updateNotification(listOf(displayName, bassTag, hpShort).filter { it.isNotBlank() }.joinToString(", "))
                if (!pipe.manualOverride && displayName != lastLoggedTone) {
                    lastLoggedTone = displayName
                    ChangeLog.add(if (pipe.autoGenre && pipe.devicePreset == null) "Tone switched to $displayName"
                        else "Tone set to $displayName")
                }
                Log.d(TAG, "Auto-EQ: $displayName gains=${combined.map { "%.1f".format(it) }}")
            }
        }
    }

    // ── PATH B: Manual slider adjustments ─────────────────────────────
    //
    // Triggered by slider drags and explicit preset selections.
    // The received values are tone-only, so this path combines them with the
    // already-cached headphone correction and current bass boost.
    //
    // This path must be completely independent of PATH A (auto pipeline).
    // It calls EqEngine.applyGains() with NO coroutine cancellation risk and
    // NO network calls, so slider changes always reach hardware immediately.

    private fun observeManualAdjustments() {
        serviceScope.launch {
            EqState.manualBandUpdate.collectLatest { toneGains ->
                toneGains ?: return@collectLatest
                gainRamp.cancel()
                if (EqState.isBypassed.value || !EqState.isEqEnabled.value) return@collectLatest

                if (EqEngine.equalizer == null) {
                    EqEngine.initSessionZero()
                }

                var finalGains = LoudnessCompensation.add(
                    EqProfileManager.combineGains(
                        toneGains,
                        EqState.currentHeadphoneCorrection.value,
                        EqState.appliedBassBoost.value
                    ),
                    EqState.appliedLoudness.value
                )
                if (EqState.levelMatchActive.value) finalGains = LevelMatch.apply(finalGains, LevelMatch.offset(finalGains))
                val protectionMode = EqState.outputProtectionMode
                val useSafeProtection =
                    protectionMode == OutputProtectionMode.SAFE ||
                        (protectionMode == OutputProtectionMode.BALANCED &&
                            !EqEngine.isLimiterProtectionActive())
                if (useSafeProtection) {
                    finalGains = applyLimitOutputGain(finalGains)
                }

                EqEngine.applyGains(finalGains)
                EqState.updateOutputGains(finalGains)
                updateNotification("Custom EQ")
                Log.d(TAG, "Manual EQ: ${finalGains.map { "%.1f".format(it) }}")
            }
        }
    }

    private data class EqPipe(
        val genre: String, val headphone: String, val enabled: Boolean,
        val correctionSelection: HeadphoneCorrectionSelection,
        val bypassed: Boolean,
        val outputProtectionMode: OutputProtectionMode,
        val mono: Boolean, val bassBoost: Float, val smoothTransitions: Boolean,
        val autoGenre: Boolean, val baseToneGains: FloatArray, val toneName: String,
        val manualOverride: Boolean,
        val customPresets: Map<String, List<Float>> = emptyMap(),
        /** User versions of built-in curves (FEATURE_CUSTOM_GENRE_CURVES.md). */
        val genreOverrides: Map<String, List<Float>> = emptyMap(),
        val correctionSuppressed: Boolean = false,
        val genreStrength: Float = GenreStrength.DEFAULT,
        /** The connected device always uses this preset (FEATURES_NEXT_PLAN.md §4). */
        val devicePreset: String? = null,
        val loudness: List<Float> = LoudnessCompensation.ZERO,
        val levelMatch: Boolean = false
    )

    private data class SourceState(
        val genre: String,
        val headphone: String,
        val enabled: Boolean,
        val bypassed: Boolean,
        val correctionSelection: HeadphoneCorrectionSelection
    )

    private data class AutoToneState(
        val autoGenre: Boolean,
        val baseToneGains: FloatArray,
        val toneName: String,
        val manualOverride: Boolean,
        val customPresets: Map<String, List<Float>>,
        val genreOverrides: Map<String, List<Float>>,
        val devicePreset: String?
    )

    private data class AudioSettingsState(
        val outputProtectionMode: OutputProtectionMode,
        val mono: Boolean,
        val bassBoost: Float,
        val smoothTransitions: Boolean,
        /** Phone speaker / "Not headphones" device: apply no headphone correction. */
        val correctionSuppressed: Boolean,
        val genreStrength: Float,
        val loudness: List<Float>,
        val levelMatch: Boolean
    )

    private data class AutoSnapshot(
        val genre: String,
        val correctionSelection: HeadphoneCorrectionSelection,
        val headphoneCorrection: FloatArray,
        val baseToneGains: FloatArray,
        val toneName: String,
        val autoGenre: Boolean,
        val manualOverride: Boolean,
        val enabled: Boolean,
        val bypassed: Boolean,
        val bassBoost: Float,
        val mono: Boolean,
        val outputProtectionMode: OutputProtectionMode,
        val smoothTransitions: Boolean,
        val genreStrength: Float,
        val devicePreset: String?,
        val loudness: List<Float>,
        val levelMatch: Boolean,
        val outputGains: FloatArray
    ) {
        fun requiresSnap(pipe: EqPipe): Boolean =
            enabled != pipe.enabled || bypassed != pipe.bypassed ||
                bassBoost != pipe.bassBoost || loudness != pipe.loudness || levelMatch != pipe.levelMatch || mono != pipe.mono ||
                outputProtectionMode != pipe.outputProtectionMode ||
                smoothTransitions != pipe.smoothTransitions ||
                autoGenre != pipe.autoGenre || devicePreset != pipe.devicePreset ||
                toneName != pipe.toneName || !baseToneGains.contentEquals(pipe.baseToneGains)

        fun canRampTo(pipe: EqPipe, correction: FloatArray, output: FloatArray): Boolean =
            !requiresSnap(pipe) && !outputGains.contentEquals(output) &&
                (genre != pipe.genre || correctionSelection != pipe.correctionSelection ||
                    genreStrength != pipe.genreStrength ||
                    !headphoneCorrection.contentEquals(correction))

        companion object {
            fun from(pipe: EqPipe, correction: FloatArray, output: FloatArray) = AutoSnapshot(
                pipe.genre, pipe.correctionSelection, correction.copyOf(),
                pipe.baseToneGains.copyOf(), pipe.toneName, pipe.autoGenre,
                pipe.manualOverride, pipe.enabled, pipe.bypassed, pipe.bassBoost,
                pipe.mono, pipe.outputProtectionMode, pipe.smoothTransitions,
                pipe.genreStrength, pipe.devicePreset, pipe.loudness, pipe.levelMatch, output.copyOf()
            )
        }
    }

    // ── Limit Output Gain ─────────────────────────────────────────────

    private fun applyLimitOutputGain(gains: FloatArray): FloatArray {
        val maxBoost = gains.maxOrNull() ?: 0f
        if (maxBoost <= LIMIT_OUTPUT_GAIN_DB) return gains
        val scale = LIMIT_OUTPUT_GAIN_DB / maxBoost
        return FloatArray(gains.size) { i -> (gains[i] * scale).coerceIn(-12f, 12f) }
    }

    // ── Force Mono ────────────────────────────────────────────────────

    private fun setMonoOutput(enabled: Boolean) {
        try {
            (getSystemService(AUDIO_SERVICE) as AudioManager)
                .setParameters("mono_output=${if (enabled) "1" else "0"}")
        } catch (e: Exception) { Log.w(TAG, "Force mono unsupported: ${e.message}") }
    }

    // ── Headphone correction ──────────────────────────────────────────
    private suspend fun resolveHeadphoneCorrection(headphoneName: String): FloatArray {
        EqState.updateHeadphoneCorrectionStatus(
            HeadphoneCorrectionStatus.Loading(headphoneName)
        )
        headphoneEqCache[headphoneName]?.let {
            EqState.updateHeadphoneCorrectionStatus(
                HeadphoneCorrectionStatus.Cached(headphoneName)
            )
            return it
        }

        HeadphoneEqDiskCache.load(this, headphoneName)?.let {
            headphoneEqCache[headphoneName] = it
            EqState.updateHeadphoneCorrectionStatus(
                HeadphoneCorrectionStatus.Cached(headphoneName)
            )
            return it
        }

        val downloadedGains = try {
            HeadphoneDataRepository.getProfileGains(this, headphoneName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = e.message ?: "Gain values could not be downloaded. Flat correction is active."
            Log.w(TAG, "HP EQ download failed for '$headphoneName': $message", e)
            EqState.updateHeadphoneCorrectionStatus(
                HeadphoneCorrectionStatus.Failed(headphoneName, message)
            )
            return FloatArray(8)
        }

        headphoneEqCache[headphoneName] = downloadedGains
        HeadphoneEqDiskCache.save(this, headphoneName, downloadedGains)
        EqState.updateHeadphoneCorrectionStatus(
            HeadphoneCorrectionStatus.Downloaded(headphoneName)
        )
        Log.d(TAG, "HP EQ cached: $headphoneName → ${downloadedGains.map { "%.1f".format(it) }}")
        return downloadedGains
    }

    // ── Notification ──────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "EQify Audio Engine", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Active EQ profile"; setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(subtitle: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val toggleIntent = PendingIntent.getService(
            this, 1,
            Intent(this, EqProcessingService::class.java).apply { action = ACTION_TOGGLE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val sleep = SleepTimer.state.value
        val sleepLabel = SleepTimer.label(sleep, SystemClock.elapsedRealtime())
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("EQify")
            .setContentText(listOfNotNull(subtitle.takeIf { it.isNotBlank() }, sleepLabel).joinToString(" · "))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openApp)
            .addAction(android.R.drawable.ic_media_pause,
                if (EqState.isEqEnabled.value) "Pause EQ" else "Resume EQ", toggleIntent)
        if (showBassButton) {
            val bassIntent = PendingIntent.getService(
                this, 2,
                Intent(this, EqProcessingService::class.java).apply { action = ACTION_CYCLE_BASS },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_lock_silent_mode_off,
                NotificationControls.bassLabel(EqState.bassBoostLevel.value), bassIntent)
        }
        if (sleep != SleepTimer.State.Off) {
            val cancelSleep = PendingIntent.getService(
                this, 3,
                Intent(this, EqProcessingService::class.java).apply { action = ACTION_SLEEP_CANCEL },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel,
                if (sleep == SleepTimer.State.PlayerKeptPlaying) "Dismiss" else "Cancel sleep", cancelSleep)
        }
        return builder.setOngoing(true).setSilent(true).build()
    }

    private fun updateNotification(subtitle: String) {
        lastNotificationText = subtitle
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(subtitle))
    }
}

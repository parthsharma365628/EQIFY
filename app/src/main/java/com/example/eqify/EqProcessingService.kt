package com.example.eqify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioManager
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

class EqProcessingService : Service() {

    companion object {
        private const val TAG = "EqProcessingService"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID      = "eqify_eq_channel"
        const val ACTION_START    = "com.example.eqify.ACTION_START"
        const val ACTION_STOP     = "com.example.eqify.ACTION_STOP"
        const val ACTION_TOGGLE   = "com.example.eqify.ACTION_TOGGLE"
        const val ACTION_SET_ENABLED = "com.example.eqify.ACTION_SET_ENABLED"
        const val EXTRA_ENABLED = "enabled"
        const val ACTION_CLEAR_HEADPHONE_CACHE =
            "com.example.eqify.ACTION_CLEAR_HEADPHONE_CACHE"
        private const val LIMIT_OUTPUT_GAIN_DB = 6f
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val headphoneEqCache = mutableMapOf<String, FloatArray>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
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
        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP   -> stopSelf()
            ACTION_TOGGLE -> {
                val enabled = !EqState.isEqEnabled.value
                EqState.setEnabled(enabled)
                serviceScope.launch {
                    (application as EqifyApplication).repository.setEqEnabled(enabled)
                }
            }
            ACTION_SET_ENABLED -> {
                val enabled = intent.getBooleanExtra(EXTRA_ENABLED, true)
                EqState.setEnabled(enabled)
                serviceScope.launch {
                    (application as EqifyApplication).repository.setEqEnabled(enabled)
                }
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
        setMonoOutput(false)
        serviceScope.cancel()
        EqState.setServiceRunning(false)
        Log.d(TAG, "Service destroyed")
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
                    EqState.isBypassed
                ) { g, h, e, bypassed -> SourceState(g, h, e, bypassed) },
                combine(
                    repository.limitOutputGain,
                    repository.forceMono,
                    EqState.bassBoostLevel
                ) { l, m, b -> Triple(l, m, b) },
                combine(
                    repository.autoGenreDetection,
                    EqState.baseToneGains,
                    EqState.tonePresetName,
                    EqState.manualOverrideActive
                ) { a, bt, tn, manual -> AutoToneState(a, bt, tn, manual) }
            ) { t1, t2, t3 ->
                EqPipe(
                    genre         = t1.genre,
                    headphone     = t1.headphone,
                    enabled       = t1.enabled,
                    bypassed      = t1.bypassed,
                    limitGain     = t2.first,
                    mono          = t2.second,
                    bassBoost     = t2.third,
                    autoGenre     = t3.autoGenre,
                    baseToneGains = t3.baseToneGains,
                    toneName      = t3.toneName,
                    manualOverride = t3.manualOverride
                )
            }.collectLatest { pipe ->
                EqState.setLimitOutputGain(pipe.limitGain)
                setMonoOutput(pipe.mono)

                if (!pipe.enabled || pipe.bypassed) {
                    EqEngine.applyFlat()
                    EqState.updateOutputGains(FloatArray(8))
                    updateNotification(if (pipe.bypassed) "EQ bypassed for comparison" else "EQ paused")
                    return@collectLatest
                }

                if (EqEngine.equalizer == null) EqEngine.initSessionZero()

                // Fetch headphone correction (cached after first fetch — no repeated calls)
                val hpCorrection = resolveHeadphoneCorrection(pipe.headphone)
                EqState.updateHeadphoneCorrection(hpCorrection)

                val tonePart: FloatArray = when {
                    pipe.manualOverride ->
                        pipe.baseToneGains.copyOf()
                    pipe.autoGenre && pipe.genre.isNotBlank() ->
                        EqProfileManager.resolveGenreGains(pipe.genre)
                    pipe.autoGenre ->
                        EqProfileManager.defaultProfiles["Flat"]!!.copyOf()
                    else ->
                        pipe.baseToneGains.copyOf()
                }

                var combined = EqProfileManager.combineGains(
                    tonePart,
                    hpCorrection,
                    pipe.bassBoost
                )

                if (pipe.limitGain) combined = applyLimitOutputGain(combined)

                val displayName = when {
                    pipe.manualOverride -> pipe.toneName.ifBlank { "Custom" }
                    pipe.autoGenre && pipe.genre.isNotBlank() ->
                        EqProfileManager.displayNameForGenre(pipe.genre)
                    pipe.autoGenre -> "Flat"
                    else -> pipe.toneName.ifBlank { "Flat" }
                }
                val bassTag = if (pipe.bassBoost > 0f) " · Bass +${pipe.bassBoost.toInt()}dB" else ""

                EqState.applyComputedOutput(displayName, tonePart, combined)
                EqEngine.applyGains(combined)

                val hpShort = pipe.headphone.split(" ").takeLast(2).joinToString(" ")
                updateNotification("$displayName$bassTag · $hpShort")
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
                if (EqState.isBypassed.value) return@collectLatest

                if (EqEngine.equalizer == null) {
                    EqEngine.initSessionZero()
                    delay(100)
                }

                var finalGains = EqProfileManager.combineGains(
                    toneGains,
                    EqState.currentHeadphoneCorrection.value,
                    EqState.bassBoostLevel.value
                )
                if (EqState.isLimitOutputGain) finalGains = applyLimitOutputGain(finalGains)

                EqEngine.applyGains(finalGains)
                EqState.updateOutputGains(finalGains)
                updateNotification("Custom EQ")
                Log.d(TAG, "Manual EQ: ${finalGains.map { "%.1f".format(it) }}")
            }
        }
    }

    private data class EqPipe(
        val genre: String, val headphone: String, val enabled: Boolean,
        val bypassed: Boolean,
        val limitGain: Boolean, val mono: Boolean, val bassBoost: Float,
        val autoGenre: Boolean, val baseToneGains: FloatArray, val toneName: String,
        val manualOverride: Boolean
    )

    private data class SourceState(
        val genre: String,
        val headphone: String,
        val enabled: Boolean,
        val bypassed: Boolean
    )

    private data class AutoToneState(
        val autoGenre: Boolean,
        val baseToneGains: FloatArray,
        val toneName: String,
        val manualOverride: Boolean
    )

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

        val apiResult = fetchHeadphoneEqFromApi(headphoneName)
        if (apiResult == null) {
            Log.w(TAG, "HP EQ not found for '$headphoneName' — using flat until retry")
            EqState.updateHeadphoneCorrectionStatus(
                HeadphoneCorrectionStatus.Unavailable(headphoneName)
            )
            return FloatArray(8)
        }

        headphoneEqCache[headphoneName] = apiResult
        HeadphoneEqDiskCache.save(this, headphoneName, apiResult)
        EqState.updateHeadphoneCorrectionStatus(
            HeadphoneCorrectionStatus.Downloaded(headphoneName)
        )
        Log.d(TAG, "HP EQ cached: $headphoneName → ${apiResult.map { "%.1f".format(it) }}")
        return apiResult
    }

    private suspend fun fetchHeadphoneEqFromApi(headphoneName: String): FloatArray? {
        return try {
            withContext(Dispatchers.IO) {
                val response = RetrofitClient.apiService.getHeadphoneEq(headphoneName)
                FloatArray(8) { i ->
                    val targetHz = EqProfileManager.BAND_CENTERS_HZ[i]
                    response.bands.minByOrNull { abs(it.frequencyHz - targetHz) }?.gainDb ?: 0f
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "HP EQ API failed for '$headphoneName': ${e.message}")
            null
        }
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
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("EQify")
            .setContentText(subtitle)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openApp)
            .addAction(android.R.drawable.ic_media_pause,
                if (EqState.isEqEnabled.value) "Pause EQ" else "Resume EQ", toggleIntent)
            .setOngoing(true).setSilent(true).build()
    }

    private fun updateNotification(subtitle: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(subtitle))
    }
}

package com.example.eqify

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService

/**
 * Phone side of the Wear OS app (FEATURES_BATCH4_PLAN.md W).
 *
 * The watch sends small commands as messages; the phone publishes its status as one
 * DataItem. Paths and payloads are shared with the :wear module (WearProtocol there).
 */
object WearBridge {
    const val STATUS_PATH = "/eqify/status"
    const val COMMAND_PATH = "/eqify/command"

    // Commands from the watch (an allowlist, like automation).
    const val CMD_TOGGLE = "toggle"
    const val CMD_BASS = "bass"
    const val CMD_SLEEP_30 = "sleep30"
    const val CMD_SLEEP_CANCEL = "sleepcancel"

    private var lastPublished: String? = null

    /** Called through ExternalStatus whenever status, bass or the sleep timer changes. */
    fun publishStatus(context: Context) {
        val running = EqState.isServiceRunning.value
        val enabled = EqState.isEqEnabled.value
        val status = when {
            !running -> "EQify isn't running"
            !enabled -> "EQ paused"
            EqState.isBypassed.value -> "EQ bypassed"
            else -> "${EqState.activePresetName.value.ifBlank { "EQ" }} active"
        }
        val bass = NotificationControls.bassLabel(EqState.bassBoostLevel.value)
        val sleep = SleepTimer.label(SleepTimer.state.value, SystemClock.elapsedRealtime()).orEmpty()
        val on = running && enabled
        val key = "$status|$bass|$sleep|$on"
        if (key == lastPublished) return
        lastPublished = key
        runCatching {
            val request = PutDataMapRequest.create(STATUS_PATH).apply {
                dataMap.putString("status", status)
                dataMap.putString("bass", bass)
                dataMap.putString("sleep", sleep)
                dataMap.putBoolean("on", on)
                dataMap.putBoolean("sleeping", SleepTimer.state.value != SleepTimer.State.Off)
                dataMap.putLong("at", System.currentTimeMillis())
            }.asPutDataRequest().setUrgent()
            Wearable.getDataClient(context.applicationContext).putDataItem(request)
                .addOnFailureListener { Log.d("WearBridge", "No watch reachable: ${it.message}") }
        }.onFailure { Log.d("WearBridge", "Wearable API unavailable: ${it.message}") }
    }
}

/** Receives watch commands. Acts only through the running EQ service (Android 12 start limits). */
class WearCommandService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WearBridge.COMMAND_PATH) return
        val command = runCatching { String(event.data, Charsets.UTF_8) }.getOrNull() ?: return
        val intent = Intent(this, EqProcessingService::class.java).apply {
            when (command) {
                WearBridge.CMD_TOGGLE -> {
                    action = EqProcessingService.ACTION_SET_ENABLED
                    putExtra(EqProcessingService.EXTRA_ENABLED, !EqState.isEqEnabled.value)
                }
                WearBridge.CMD_BASS -> {
                    action = EqProcessingService.ACTION_SET_BASS
                    putExtra(EqProcessingService.EXTRA_LEVEL, NotificationControls.nextBass(EqState.bassBoostLevel.value))
                }
                WearBridge.CMD_SLEEP_30 -> {
                    action = EqProcessingService.ACTION_SLEEP_START
                    putExtra(EqProcessingService.EXTRA_MINUTES, 30)
                }
                WearBridge.CMD_SLEEP_CANCEL -> action = EqProcessingService.ACTION_SLEEP_CANCEL
                else -> return
            }
        }
        if (!EqState.isServiceRunning.value) {
            WearBridge.publishStatus(this)          // tells the watch EQify isn't running
            return
        }
        runCatching { startService(intent) }.onFailure { Log.w("WearCommand", "Could not reach the EQ service", it) }
    }
}

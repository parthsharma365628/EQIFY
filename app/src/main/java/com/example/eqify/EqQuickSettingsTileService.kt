package com.example.eqify

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class EqQuickSettingsTileService : TileService() {
    companion object {
        private const val TAG = "EqQuickSettingsTile"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listeningJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        listeningJob?.cancel()
        listeningJob = scope.launch {
            val repository = (application as EqifyApplication).repository
            combine(
                repository.processingSnapshot,
                EqState.isServiceRunning
            ) { snapshot, serviceRunning -> snapshot to serviceRunning }
                .collect { (snapshot, serviceRunning) ->
                    updateTile(snapshot, serviceRunning)
                }
        }
    }

    override fun onStopListening() {
        listeningJob?.cancel()
        listeningJob = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        scope.launch {
            val repository = (application as EqifyApplication).repository
            val current = repository.processingSnapshot.first()
            val serviceRunning = EqState.isServiceRunning.value
            val shouldEnable = !current.requestedEnabled ||
                !serviceRunning || current.status == EqProcessingStatus.UNAVAILABLE

            repository.setEqEnabled(shouldEnable)
            updateTile(
                current.copy(
                    requestedEnabled = shouldEnable,
                    status = if (shouldEnable) EqProcessingStatus.STARTING
                    else EqProcessingStatus.PAUSED
                ),
                serviceRunning = shouldEnable
            )

            try {
                ContextCompat.startForegroundService(
                    this@EqQuickSettingsTileService,
                    Intent(this@EqQuickSettingsTileService, EqProcessingService::class.java).apply {
                        action = EqProcessingService.ACTION_SET_ENABLED
                        putExtra(EqProcessingService.EXTRA_ENABLED, shouldEnable)
                    }
                )
            } catch (e: RuntimeException) {
                Log.e(TAG, "Could not send EQ command from tile", e)
                repository.setProcessingSnapshot(
                    EqProcessingStatus.UNAVAILABLE,
                    current.activeProfile
                )
                updateTile(
                    current.copy(
                        requestedEnabled = shouldEnable,
                        status = EqProcessingStatus.UNAVAILABLE
                    ),
                    serviceRunning = false
                )
            }
        }
    }

    private fun updateTile(snapshot: EqProcessingSnapshot, serviceRunning: Boolean) {
        val effectiveStatus = when {
            !snapshot.requestedEnabled -> EqProcessingStatus.PAUSED
            !serviceRunning -> EqProcessingStatus.UNAVAILABLE
            else -> snapshot.status
        }
        val statusText = when (effectiveStatus) {
            EqProcessingStatus.ACTIVE ->
                snapshot.activeProfile.takeIf { it.isNotBlank() }?.let { "$it active" }
                    ?: "EQ active"
            EqProcessingStatus.PAUSED -> "EQ paused"
            EqProcessingStatus.BYPASSED -> "EQ bypassed"
            EqProcessingStatus.STARTING -> "Starting EQ…"
            EqProcessingStatus.UNAVAILABLE -> "Tap to retry"
        }

        val tile = qsTile ?: return
        tile.state = if (effectiveStatus == EqProcessingStatus.ACTIVE) {
            Tile.STATE_ACTIVE
        } else {
            // Keep unavailable states tappable so the user can retry attachment.
            Tile.STATE_INACTIVE
        }
        tile.label = "EQify"
        tile.contentDescription = "EQify, $statusText"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = statusText
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            tile.stateDescription = statusText
        }
        tile.updateTile()
    }

    override fun onDestroy() {
        listeningJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }
}

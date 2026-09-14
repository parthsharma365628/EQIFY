package com.example.eqify

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class EqQuickSettingsTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onStartListening() {
        super.onStartListening()
        scope.launch {
            val enabled = (application as EqifyApplication).repository.isEqEnabled.first()
            updateTile(enabled)
        }
    }

    override fun onClick() {
        super.onClick()
        scope.launch {
            val repository = (application as EqifyApplication).repository
            val enabled = !repository.isEqEnabled.first()
            updateTile(enabled)
            ContextCompat.startForegroundService(
                this@EqQuickSettingsTileService,
                Intent(this@EqQuickSettingsTileService, EqProcessingService::class.java).apply {
                    action = EqProcessingService.ACTION_SET_ENABLED
                    putExtra(EqProcessingService.EXTRA_ENABLED, enabled)
                }
            )
        }
    }

    private fun updateTile(enabled: Boolean) {
        qsTile?.apply {
            state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "EQify"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                subtitle = if (enabled) "EQ active" else "EQ paused"
            }
            updateTile()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

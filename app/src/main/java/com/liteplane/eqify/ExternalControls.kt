package com.liteplane.eqify

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * App shortcuts (long-press the icon; FEATURES_BATCH3_PLAN.md §E). A no-UI activity:
 * it is in the foreground for a moment, so it may start the EQ service, then finishes.
 */
class ShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val command = when (intent?.action) {
            ACTION_TOGGLE -> Intent(this, EqProcessingService::class.java).apply {
                action = EqProcessingService.ACTION_SET_ENABLED
                putExtra(EqProcessingService.EXTRA_ENABLED, !EqState.isEqEnabled.value || !EqState.isServiceRunning.value)
            }
            ACTION_BASS -> Intent(this, EqProcessingService::class.java).apply {
                action = EqProcessingService.ACTION_CYCLE_BASS
            }
            ACTION_SLEEP_30 -> Intent(this, EqProcessingService::class.java).apply {
                action = EqProcessingService.ACTION_SLEEP_START
                putExtra(EqProcessingService.EXTRA_MINUTES, 30)
            }
            else -> null
        }
        if (command != null) {
            runCatching { ContextCompat.startForegroundService(this, command) }
                .onFailure { Log.w("ShortcutActivity", "Shortcut failed", it) }
        }
        finish()
    }

    companion object {
        const val ACTION_TOGGLE = "com.liteplane.eqify.shortcut.TOGGLE"
        const val ACTION_BASS = "com.liteplane.eqify.shortcut.BASS"
        const val ACTION_SLEEP_30 = "com.liteplane.eqify.shortcut.SLEEP_30"
    }
}

/**
 * Tasker and other automation apps (FEATURES_BATCH3_PLAN.md §E). Exported, but it
 * ignores everything unless Settings → Automation is on, and accepts only the
 * [AutomationCommand] allowlist. It never returns data.
 *
 * Android 12+ blocks starting a foreground service from a background broadcast,
 * so commands act through the running service; otherwise the setting is saved
 * and applies when EQify next starts (the sleep timer needs the service).
 */
class AutomationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val raw = intent.extras?.let { b -> b.keySet().associateWith { @Suppress("DEPRECATION") b.get(it) } }.orEmpty()
        val command = AutomationCommand.parse(intent.action, raw) ?: return
        val app = context.applicationContext as? EqifyApplication ?: return
        val pending = goAsync()
        scope.launch {
            try {
                if (!app.repository.automationEnabled.first()) return@launch
                if (EqState.isServiceRunning.value && sendToService(app, command)) return@launch
                saveForLater(app, command)
            } catch (e: Exception) {
                Log.w(TAG, "Automation command failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private fun sendToService(context: Context, command: AutomationCommand): Boolean {
        val intent = Intent(context, EqProcessingService::class.java).apply {
            when (command) {
                is AutomationCommand.SetEq -> {
                    action = EqProcessingService.ACTION_SET_ENABLED
                    putExtra(EqProcessingService.EXTRA_ENABLED, command.enabled)
                }
                is AutomationCommand.SetBass -> {
                    action = EqProcessingService.ACTION_SET_BASS
                    putExtra(EqProcessingService.EXTRA_LEVEL, command.levelDb)
                }
                is AutomationCommand.SetPreset -> {
                    action = EqProcessingService.ACTION_SET_PRESET
                    putExtra(EqProcessingService.EXTRA_NAME, command.name)
                }
                is AutomationCommand.SetAutoGenre -> {
                    action = EqProcessingService.ACTION_SET_AUTO_GENRE
                    putExtra(EqProcessingService.EXTRA_ENABLED, command.enabled)
                }
                is AutomationCommand.Sleep -> {
                    action = if (command.minutes == 0) EqProcessingService.ACTION_SLEEP_CANCEL
                        else EqProcessingService.ACTION_SLEEP_START
                    putExtra(EqProcessingService.EXTRA_MINUTES, command.minutes)
                }
            }
        }
        // The service is in the foreground, so a plain start command is allowed.
        return runCatching { context.startService(intent) }.isSuccess
    }

    private suspend fun saveForLater(app: EqifyApplication, command: AutomationCommand) {
        val repo = app.repository
        when (command) {
            is AutomationCommand.SetEq -> repo.setEqEnabled(command.enabled)
            is AutomationCommand.SetBass -> repo.setBassBoostLevel(command.levelDb)
            is AutomationCommand.SetAutoGenre -> repo.setAutoGenreDetection(command.enabled)
            is AutomationCommand.SetPreset -> {
                val names = repo.customPresetNames.first() + EqProfileManager.defaultProfiles.keys
                names.firstOrNull { it.equals(command.name, ignoreCase = true) }?.let { repo.setLastPreset(it) }
            }
            is AutomationCommand.Sleep -> Log.i(TAG, "Sleep timer needs EQify running; ignored")
        }
    }

    companion object {
        private const val TAG = "AutomationReceiver"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}

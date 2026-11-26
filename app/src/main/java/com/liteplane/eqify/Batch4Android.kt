package com.liteplane.eqify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.SystemClock
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * One place that tells every outside surface the state changed (FEATURES_BATCH4_PLAN.md §4):
 * the widget, the Bass and Sleep tiles, and the watch.
 */
object ExternalStatus {
    fun refresh(context: Context) {
        EqifyWidget.refresh(context)
        runCatching {
            TileService.requestListeningState(context, ComponentName(context, BassTileService::class.java))
            TileService.requestListeningState(context, ComponentName(context, SleepTileService::class.java))
        }
        WearBridge.publishStatus(context)
    }
}

/** Safe-listening alerts (FEATURES_BATCH4_PLAN.md, research section). */
object SafeListeningAlerts {
    const val CHANNEL_ID = "eqify_safe_listening"
    private const val NOTIFICATION_ID = 1002

    fun notify(context: Context, percent: Int) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Safe listening",
            NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Weekly listening allowance reminders (WHO safe-listening guidance)"
        })
        fun service(action: String, code: Int) = PendingIntent.getService(context, code,
            Intent(context, EqProcessingService::class.java).apply { this.action = action },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(context, 20, Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("You've used about $percent% of this week's listening allowance")
            .setContentText("An estimate based on your volume. Lowering it lets you listen longer safely.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "EQify estimates you've used about $percent% of the weekly sound allowance the WHO suggests " +
                    "(based on your volume and headphones, not measured). Turning the volume down a little makes a " +
                    "big difference: every 3 dB lower doubles your safe listening time."))
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, "Lower volume", service(EqProcessingService.ACTION_LOWER_VOLUME, 21))
            .addAction(0, "Turn on volume limit", service(EqProcessingService.ACTION_ENABLE_VOLUME_LIMIT, 22))
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
            .onFailure { Log.w("SafeListening", "Could not post alert", it) }
    }
}

/** Quick-tile extra: cycles bass (FEATURES_BATCH4_PLAN.md S3). */
class BassTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        job?.cancel()
        job = scope.launch {
            combine(EqState.bassBoostLevel, EqState.isServiceRunning) { bass, running -> bass to running }
                .collect { (bass, running) -> update(bass, running) }
        }
    }

    override fun onStopListening() { job?.cancel(); super.onStopListening() }

    override fun onClick() {
        super.onClick()
        // Tile taps may start the foreground service; the service routes bass through the device rules.
        runCatching {
            ContextCompat.startForegroundService(this, Intent(this, EqProcessingService::class.java).apply {
                action = EqProcessingService.ACTION_SET_BASS
                putExtra(EqProcessingService.EXTRA_LEVEL, NotificationControls.nextBass(EqState.bassBoostLevel.value))
            })
        }.onFailure { Log.w("BassTile", "Could not change bass", it) }
    }

    private fun update(bass: Float, running: Boolean) {
        val tile = qsTile ?: return
        tile.label = "Bass"
        tile.state = if (bass > 0f && running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        val text = NotificationControls.bassLabel(bass).removePrefix("Bass: ")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) tile.subtitle = text
        tile.contentDescription = "Bass boost, $text"
        tile.icon = Icon.createWithResource(this, R.drawable.ic_sym_equalizer)
        tile.updateTile()
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}

/** Quick-tile extra: sleep timer 30 min, or cancel (FEATURES_BATCH4_PLAN.md S3). */
class SleepTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        job?.cancel()
        job = scope.launch { SleepTimer.state.collect { update(it) } }
    }

    override fun onStopListening() { job?.cancel(); super.onStopListening() }

    override fun onClick() {
        super.onClick()
        val running = SleepTimer.state.value != SleepTimer.State.Off
        runCatching {
            ContextCompat.startForegroundService(this, Intent(this, EqProcessingService::class.java).apply {
                if (running) action = EqProcessingService.ACTION_SLEEP_CANCEL
                else {
                    action = EqProcessingService.ACTION_SLEEP_START
                    putExtra(EqProcessingService.EXTRA_MINUTES, 30)
                }
            })
        }.onFailure { Log.w("SleepTile", "Could not change the sleep timer", it) }
    }

    private fun update(state: SleepTimer.State) {
        val tile = qsTile ?: return
        tile.label = "Sleep timer"
        tile.state = if (state == SleepTimer.State.Off) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        val text = SleepTimer.label(state, SystemClock.elapsedRealtime()) ?: "Tap for 30 min"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) tile.subtitle = text
        tile.contentDescription = "Sleep timer, $text"
        tile.icon = Icon.createWithResource(this, R.drawable.ic_sym_bedtime)
        tile.updateTile()
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}

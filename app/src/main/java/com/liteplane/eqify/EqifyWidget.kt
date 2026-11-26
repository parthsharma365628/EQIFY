package com.liteplane.eqify

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** What the widget says (FEATURES_BATCH3_PLAN.md §G). Pure; unit-tested. */
data class WidgetText(val status: String, val toggleLabel: String, val bassLabel: String, val nextEnabled: Boolean) {
    companion object {
        fun from(
            snapshot: EqProcessingSnapshot,
            serviceRunning: Boolean,
            bassDb: Float,
            sleepLabel: String?
        ): WidgetText {
            val status = when {
                !snapshot.requestedEnabled -> EqProcessingStatus.PAUSED
                !serviceRunning -> EqProcessingStatus.UNAVAILABLE
                else -> snapshot.status
            }
            val line = when (status) {
                EqProcessingStatus.ACTIVE -> snapshot.activeProfile.ifBlank { "EQ" } + " active"
                EqProcessingStatus.PAUSED -> "EQ paused"
                EqProcessingStatus.BYPASSED -> "EQ bypassed"
                EqProcessingStatus.STARTING -> "Starting EQ…"
                EqProcessingStatus.UNAVAILABLE -> "Tap Resume to start"
            }
            val on = status == EqProcessingStatus.ACTIVE || status == EqProcessingStatus.BYPASSED ||
                status == EqProcessingStatus.STARTING
            return WidgetText(
                status = listOfNotNull(line, sleepLabel).joinToString(" · "),
                toggleLabel = if (on) "Pause" else "Resume",
                bassLabel = NotificationControls.bassLabel(bassDb).removePrefix("Bass: ").let { "Bass $it" },
                nextEnabled = !on
            )
        }
    }
}

/** RemoteViews widget: no extra library. State comes from the same snapshot as the QS tile. */
class EqifyWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        scope.launch {
            try { render(context.applicationContext, manager, ids) }
            catch (e: Exception) { Log.w(TAG, "Widget update failed", e) }
            finally { pending.finish() }
        }
    }

    companion object {
        private const val TAG = "EqifyWidget"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /** Called by the service whenever status, profile, bass or the sleep timer changes. */
        fun refresh(context: Context) {
            val app = context.applicationContext
            val manager = AppWidgetManager.getInstance(app) ?: return
            val ids = runCatching { manager.getAppWidgetIds(ComponentName(app, EqifyWidget::class.java)) }
                .getOrNull() ?: return
            if (ids.isEmpty()) return
            scope.launch {
                try { render(app, manager, ids) } catch (e: Exception) { Log.w(TAG, "Widget refresh failed", e) }
            }
        }

        private suspend fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val repository = (context as? EqifyApplication)?.repository
                ?: (context.applicationContext as EqifyApplication).repository
            val snapshot = repository.processingSnapshot.first()
            val running = EqState.isServiceRunning.value
            // The connected device's effective bass when running; the saved default otherwise.
            val bass = if (running) EqState.bassBoostLevel.value else repository.bassBoostLevel.first()
            val text = WidgetText.from(snapshot, running, bass,
                SleepTimer.label(SleepTimer.state.value, SystemClock.elapsedRealtime()))

            val views = RemoteViews(context.packageName, R.layout.widget_eqify).apply {
                setTextViewText(R.id.widget_status, text.status)
                setTextViewText(R.id.widget_toggle, text.toggleLabel)
                setTextViewText(R.id.widget_bass, text.bassLabel)
                setOnClickPendingIntent(R.id.widget_info, PendingIntent.getActivity(
                    context, 10, Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                // Widget taps may start the foreground service (an allowed background start).
                setOnClickPendingIntent(R.id.widget_toggle, PendingIntent.getForegroundService(
                    context, 11, Intent(context, EqProcessingService::class.java).apply {
                        action = EqProcessingService.ACTION_SET_ENABLED
                        putExtra(EqProcessingService.EXTRA_ENABLED, text.nextEnabled)
                    }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                setOnClickPendingIntent(R.id.widget_bass, PendingIntent.getForegroundService(
                    context, 12, Intent(context, EqProcessingService::class.java).apply {
                        action = EqProcessingService.ACTION_SET_BASS
                        putExtra(EqProcessingService.EXTRA_LEVEL, NotificationControls.nextBass(bass))
                    }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
            manager.updateAppWidget(ids, views)
        }
    }
}

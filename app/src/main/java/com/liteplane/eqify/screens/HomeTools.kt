package com.liteplane.eqify

import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.liteplane.eqify.ui.components.*
import com.liteplane.eqify.ui.theme.*
import kotlinx.coroutines.delay

/** Sleep timer (FEATURES_BATCH3_PLAN.md §C). */
@Composable
internal fun SleepTimerSheet(
    state: SleepTimer.State,
    endOfTrackAvailable: Boolean,
    onStart: (Int) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    val c = EqifyTheme.colors
    // Re-read the clock every few seconds so "Sleep in N min" stays current.
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(state) { while (true) { now = SystemClock.elapsedRealtime(); delay(5_000) } }
    EqSheet(
        title = "Sleep timer",
        subtitle = "Music fades out over 30 seconds, then pauses. Your volume is put back afterwards.",
        onDismiss = onDismiss
    ) {
        SleepTimer.label(state, now)?.let { label ->
            StatusBanner(label, StatusKind.ACTIVE,
                actionLabel = if (state == SleepTimer.State.PlayerKeptPlaying) "Dismiss" else "Cancel",
                onAction = onCancel)
        }
        Column(Modifier.fillMaxWidth()) {
            SleepTimer.CHOICES.forEach { minutes ->
                SettingRow(title = "$minutes minutes", icon = EqIcons.Bedtime, onClick = { onStart(minutes) })
                HorizontalDivider(color = c.outline, modifier = Modifier.padding(start = 56.dp))
            }
            if (endOfTrackAvailable) {
                SettingRow(title = "End of this track", subtitle = "Pauses when the next song starts",
                    icon = EqIcons.Music, onClick = { onStart(SleepTimer.END_OF_TRACK) })
            }
        }
        Text("EQify keeps running; only the music app is paused. A timer ends if the phone restarts.",
            style = MaterialTheme.typography.bodySmall, color = c.textFaint)
    }
}

/** Test sounds (FEATURES_BATCH3_PLAN.md §D). */
@Composable
internal fun TestSoundSheet(
    playing: TestTone?,
    forceMono: Boolean,
    enabled: Boolean,
    onPlay: (TestTone) -> Unit,
    onDismiss: () -> Unit
) {
    val c = EqifyTheme.colors
    EqSheet(
        title = "Test sound",
        subtitle = "Quiet tones played through the music stream, so you hear the EQ as it is now.",
        onDismiss = onDismiss
    ) {
        if (forceMono) {
            StatusBanner("Force mono is on, so left and right play together.", StatusKind.WARNING)
        }
        val descriptions = mapOf(
            TestTone.EQ_CHECK to "Three short tones: bass, mids, treble",
            TestTone.LEFT to "Checks which earbud is which",
            TestTone.RIGHT to "Checks which earbud is which",
            TestTone.SWEEP to "10 seconds, 20 Hz to 20 kHz. Shows the EQ and your hearing range"
        )
        Column(Modifier.fillMaxWidth()) {
            TestTone.entries.forEachIndexed { i, tone ->
                SettingRow(
                    title = tone.label,
                    subtitle = if (playing == tone) "Playing…" else descriptions[tone],
                    icon = EqIcons.Play,
                    enabled = enabled && playing == null,
                    onClick = { onPlay(tone) }
                )
                if (i < TestTone.entries.size - 1)
                    HorizontalDivider(color = c.outline, modifier = Modifier.padding(start = 56.dp))
            }
        }
        if (!enabled) Text("Start EQify's audio engine to play test sounds.",
            style = MaterialTheme.typography.bodySmall, color = c.textMuted)
    }
}

/** "What changed" (FEATURES_BATCH4_PLAN.md S6): the session's automatic changes, newest first. */
@Composable
internal fun ChangesSheet(entries: List<ChangeLog.Entry>, onDismiss: () -> Unit) {
    val c = EqifyTheme.colors
    val format = remember { java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT) }
    EqSheet(title = "What changed", subtitle = "Automatic changes since EQify started. Not saved.", onDismiss = onDismiss) {
        if (entries.isEmpty()) {
            Text("Nothing yet. Changes appear here as songs, headphones and settings change.",
                style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
        }
        entries.forEach { e ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(EqSpacing.m)) {
                Text(format.format(java.util.Date(e.atMillis)), style = MaterialTheme.typography.bodySmall.tabular(),
                    color = c.textMuted, modifier = Modifier.width(64.dp))
                Text(e.text, style = MaterialTheme.typography.bodyMedium, color = c.textPrimary, modifier = Modifier.weight(1f))
            }
        }
    }
}

package com.example.eqify

import android.Manifest
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.eqify.ui.components.*
import com.example.eqify.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Live spectrum (FEATURES_BATCH4_PLAN.md A). Uses Visualizer on the output mix, which
 * Android gates behind RECORD_AUDIO; it is asked for only here. Released when closed.
 */
@Composable
internal fun SpectrumSheet(onDismiss: () -> Unit) {
    val c = EqifyTheme.colors
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED)
    }
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok; denied = !ok
    }
    var bars by remember { mutableStateOf(FloatArray(SpectrumBars.BARS)) }
    var error by remember { mutableStateOf<String?>(null) }

    if (granted) {
        LaunchedEffect(Unit) {
            val visualizer = withContext(Dispatchers.Default) {
                runCatching {
                    Visualizer(0).apply {
                        captureSize = Visualizer.getCaptureSizeRange()[1]
                        scalingMode = Visualizer.SCALING_MODE_NORMALIZED
                        enabled = true
                    }
                }.onFailure { Log.w("Spectrum", "Visualizer unavailable", it) }.getOrNull()
            }
            if (visualizer == null) {
                error = "This phone doesn't allow a live spectrum of its output."
                return@LaunchedEffect
            }
            try {
                val fft = ByteArray(visualizer.captureSize)
                val rate = visualizer.samplingRate
                while (isActive) {
                    if (visualizer.getFft(fft) == Visualizer.SUCCESS) {
                        bars = SpectrumBars.smooth(bars, SpectrumBars.fromFft(fft, rate))
                    }
                    delay(33)
                }
            } finally {
                runCatching { visualizer.enabled = false }
                runCatching { visualizer.release() }
            }
        }
    }

    EqSheet(title = "Live spectrum", subtitle = "What's playing right now, low notes on the left, high on the right.",
        onDismiss = onDismiss) {
        when {
            !granted -> {
                Text("To draw the spectrum, Android needs EQify to have the \"Record audio\" permission. " +
                    "EQify only reads its own audio output while this screen is open. It never uses the microphone " +
                    "and never saves or sends any audio.",
                    style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
                if (denied) Text("Permission was declined. You can allow it later in Android Settings → Apps → EQify.",
                    style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                PrimaryButton("Allow and show spectrum", { launcher.launch(Manifest.permission.RECORD_AUDIO) })
            }
            error != null -> StatusBanner(error!!, StatusKind.WARNING)
            else -> {
                val barColor = c.accent
                val trackColor = c.surfaceHighest
                Canvas(Modifier.fillMaxWidth().height(180.dp)) {
                    val n = bars.size
                    val gap = 3.dp.toPx()
                    val w = (size.width - gap * (n - 1)) / n
                    bars.forEachIndexed { i, v ->
                        val x = i * (w + gap)
                        drawRoundRect(trackColor, Offset(x, 0f), Size(w, size.height), CornerRadius(w / 3))
                        val h = size.height * v
                        drawRoundRect(barColor, Offset(x, size.height - h), Size(w, h), CornerRadius(w / 3))
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    Text("30 Hz", style = MaterialTheme.typography.labelSmall, color = c.textMuted, modifier = Modifier.weight(1f))
                    Text("1 kHz", style = MaterialTheme.typography.labelSmall, color = c.textMuted)
                    Spacer(Modifier.weight(1f))
                    Text("16 kHz", style = MaterialTheme.typography.labelSmall, color = c.textMuted)
                }
                Text("Shows the sound after EQify's EQ. Quiet or paused music shows little.",
                    style = MaterialTheme.typography.bodySmall, color = c.textFaint)
            }
        }
    }
}

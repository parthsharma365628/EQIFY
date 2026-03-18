package com.example.eqify

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.eqify.ui.theme.*
import kotlin.math.roundToInt

data class EqBand(
    val frequency: String,
    val gainDb: Float
)

// Pre-defined presets for the animation showcase
val presetsMap = mapOf(
    "Flat" to listOf(
        EqBand("60Hz", 0f), EqBand("170Hz", 0f), EqBand("310Hz", 0f), EqBand("600Hz", 0f),
        EqBand("1kHz", 0f), EqBand("3kHz", 0f), EqBand("6kHz", 0f), EqBand("12kHz", 0f)
    ),
    "Hip-Hop" to listOf(
        EqBand("60Hz", 5f), EqBand("170Hz", 3f), EqBand("310Hz", 0f), EqBand("600Hz", 1f),
        EqBand("1kHz", 2f), EqBand("3kHz", -1f), EqBand("6kHz", 0f), EqBand("12kHz", 1f)
    ),
    "EDM" to listOf(
        EqBand("60Hz", 6f), EqBand("170Hz", 4f), EqBand("310Hz", 1f), EqBand("600Hz", -1f),
        EqBand("1kHz", 0f), EqBand("3kHz", 2f), EqBand("6kHz", 4f), EqBand("12kHz", 5f)
    ),
    "Classical" to listOf(
        EqBand("60Hz", 0f), EqBand("170Hz", 0f), EqBand("310Hz", 1f), EqBand("600Hz", 2f),
        EqBand("1kHz", 2f), EqBand("3kHz", 1f), EqBand("6kHz", 3f), EqBand("12kHz", 4f)
    ),
    "Rock" to listOf(
        EqBand("60Hz", 3f), EqBand("170Hz", 2f), EqBand("310Hz", -1f), EqBand("600Hz", -2f),
        EqBand("1kHz", 1f), EqBand("3kHz", 3f), EqBand("6kHz", 4f), EqBand("12kHz", 3f)
    )
)

@Composable
fun EqScreen() {
    var activePreset by remember { mutableStateOf("Hip-Hop") }
    // Initialize bands with the active preset
    var bands by remember { mutableStateOf(presetsMap[activePreset]!!) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Equalizer",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            TextButton(onClick = {
                activePreset = "Flat"
                bands = presetsMap["Flat"]!!
            }) {
                Text("Reset", color = AccentPurpleLight, fontSize = 12.sp)
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .weight(1f),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Surface2Dark)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                bands.forEachIndexed { index, band ->
                    VerticalEqSlider(
                        gainDb = band.gainDb,
                        frequency = band.frequency,
                        onChange = { newGain ->
                            // Custom user edits turn off the preset highlight
                            activePreset = "Custom"
                            bands = bands.toMutableList().apply {
                                this[index] = this[index].copy(gainDb = newGain)
                            }
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Presets Row
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            presetsMap.keys.forEach { preset ->
                val isActive = preset == activePreset
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (isActive) AccentPurple.copy(alpha = 0.2f) else Surface2Dark,
                    border = BorderStroke(
                        1.dp,
                        if (isActive) AccentPurple else AccentPurple.copy(alpha = 0.1f)
                    ),
                    modifier = Modifier.clickable {
                        activePreset = preset
                        bands = presetsMap[preset]!! // Animate to new preset automatically
                    }
                ) {
                    Text(
                        text = preset,
                        fontSize = 10.sp,
                        color = if (isActive) AccentPurpleLight else TextSecondary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun VerticalEqSlider(
    gainDb: Float,
    frequency: String,
    onChange: (Float) -> Unit
) {
    val animatedGain by animateFloatAsState(
        targetValue = gainDb,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "eq_slider_animation"
    )

    var isDragging by remember { mutableStateOf(false) }
    val sliderValue = if (isDragging) gainDb else animatedGain

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxHeight()
    ) {
        // dB label
        Text(
            text = "${if (gainDb > 0) "+" else ""}${gainDb.toInt()}",
            fontSize = 10.sp,
            color = AccentPurpleLight,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(6.dp))

        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .width(40.dp),
            contentAlignment = Alignment.Center
        ) {
            // We use 'this.maxHeight' and 'this.maxWidth' to satisfy the linter
            Slider(
                value = sliderValue,
                onValueChange = { newGain ->
                    @Suppress("UNUSED_VALUE") // Suppresses the false positive
                    isDragging = true
                    val snapped = (newGain * 2).roundToInt() / 2f
                    onChange(snapped.coerceIn(-12f, 12f))
                },
                onValueChangeFinished = {
                    @Suppress("UNUSED_VALUE") // Suppresses the false positive
                    isDragging = false
                },
                valueRange = -12f..12f,
                colors = SliderDefaults.colors(
                    thumbColor = AccentPurpleLight,
                    activeTrackColor = AccentPurple,
                    inactiveTrackColor = Color.White.copy(alpha = 0.12f)
                ),
                modifier = Modifier
                    .requiredWidth(this.maxHeight)
                    .requiredHeight(this.maxWidth)
                    .graphicsLayer {
                        rotationZ = -90f
                        transformOrigin = TransformOrigin.Center
                    }
            )
        }

        Spacer(Modifier.height(6.dp))

        // Frequency label
        Text(
            text = frequency,
            fontSize = 9.sp,
            color = TextSecondary
        )
    }
}
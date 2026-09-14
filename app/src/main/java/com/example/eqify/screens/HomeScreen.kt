package com.example.eqify

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.eqify.ui.theme.*

@Composable
fun HomeScreen(
    onNavigateToHeadphones: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    viewModel: HomeViewModel = viewModel()
) {
    val trackName            by viewModel.currentTrack.collectAsState()
    val artistName           by viewModel.currentArtist.collectAsState()
    val genreUiState         by viewModel.genreUiState.collectAsState()
    val selectedHeadphone    by viewModel.selectedHeadphone.collectAsState()
    val mediaListenerEnabled by viewModel.mediaListenerEnabled.collectAsState()
    val isEqEnabled          by EqState.isEqEnabled.collectAsState()
    val activePreset         by EqState.activePresetName.collectAsState()
    val serviceRunning       by EqState.isServiceRunning.collectAsState()
    val wiredConnected       by BluetoothHeadphoneDetector.wiredConnected.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .verticalScroll(rememberScrollState())
    ) {
        TopBar(onSettingsClick = onNavigateToSettings)

        if (wiredConnected) {
            WiredHeadphoneBanner(
                onChangeClick = {
                    BluetoothHeadphoneDetector.dismissWiredBanner()
                    onNavigateToHeadphones()
                },
                onDismissClick = { BluetoothHeadphoneDetector.dismissWiredBanner() }
            )
        }

        if (mediaListenerEnabled) {
            NowPlayingCard(
                trackName    = trackName,
                artistName   = artistName,
                genreUiState = genreUiState
            )
        }

        EqStatusCard(
            enabled        = isEqEnabled,
            activePreset   = activePreset,
            serviceRunning = serviceRunning,
            onToggle       = { viewModel.setEqEnabled(it) }
        )

        HeadphoneCard(
            headphoneName = selectedHeadphone,
            onClick       = onNavigateToHeadphones
        )

        // Bass booster — reads from EqState (survives navigation)
        // and writes via HomeViewModel (persists to DataStore)
        BassBoosterCard(onLevelChange = { viewModel.setBassBoost(it) })

        Spacer(modifier = Modifier.height(16.dp))
    }
}

// ── Bass Booster ──────────────────────────────────────────────────────
// Reads from EqState.bassBoostLevel so it survives navigation.
// Writes via onLevelChange callback which goes through HomeViewModel
// to persist to DataStore and update EqState (triggering the service).

@Composable
fun BassBoosterCard(onLevelChange: (Float) -> Unit) {
    // Read from EqState singleton — never resets on navigation
    val bassLevel by EqState.bassBoostLevel.collectAsState()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape  = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Surface2Dark)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text       = "Bass Booster",
                        fontSize   = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color      = TextPrimary
                    )
                    Text(
                        text     = if (bassLevel == 0f) "Off" else "+${bassLevel.toInt()}dB sub-bass",
                        fontSize = 10.sp,
                        color    = if (bassLevel > 0f) AccentPurpleLight else TextSecondary
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0f, 3f, 6f, 9f).forEach { level ->
                        val isActive = bassLevel == level
                        Surface(
                            shape  = RoundedCornerShape(8.dp),
                            color  = if (isActive) AccentPurple else Surface2Dark,
                            border = BorderStroke(
                                1.dp,
                                if (isActive) AccentPurple else AccentPurple.copy(alpha = 0.2f)
                            ),
                            modifier = Modifier.clickable {
                                // Write via ViewModel → EqState + DataStore
                                // The service reacts via bassBoostLevel in combine
                                onLevelChange(level)
                            }
                        ) {
                            Text(
                                text       = if (level == 0f) "Off" else "+${level.toInt()}",
                                fontSize   = 10.sp,
                                color      = if (isActive) Color.White else TextSecondary,
                                fontWeight = FontWeight.SemiBold,
                                modifier   = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Wired headphone banner ────────────────────────────────────────────

@Composable
fun WiredHeadphoneBanner(onChangeClick: () -> Unit, onDismissClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape  = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = AccentPurple.copy(alpha = 0.12f))
    ) {
        Row(
            modifier          = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("🎧", fontSize = 22.sp)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text       = "Wired headphones detected",
                    fontSize   = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color      = TextPrimary
                )
                Text(
                    text     = "Is your headphone selection still correct?",
                    fontSize = 11.sp,
                    color    = TextSecondary
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                TextButton(
                    onClick        = onChangeClick,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text("Change", color = AccentPurpleLight, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
                TextButton(
                    onClick        = onDismissClick,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text("Dismiss", color = TextSecondary, fontSize = 11.sp)
                }
            }
        }
    }
}

// ── Top bar ───────────────────────────────────────────────────────────

@Composable
fun TopBar(onSettingsClick: () -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Text(
            text       = "EQify",
            fontSize   = 22.sp,
            fontWeight = FontWeight.Bold,
            color      = AccentPurpleLight
        )
        IconButton(onClick = onSettingsClick) {
            Text("⚙️", fontSize = 20.sp)
        }
    }
}

// ── Now Playing card ──────────────────────────────────────────────────

@Composable
fun NowPlayingCard(trackName: String, artistName: String, genreUiState: GenreUiState) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape  = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Surface2Dark)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text          = "NOW PLAYING",
                fontSize      = 9.sp,
                color         = NeonCyan,
                letterSpacing = 2.sp
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text       = trackName,
                fontSize   = 16.sp,
                fontWeight = FontWeight.Bold,
                color      = TextPrimary
            )
            if (artistName.isNotBlank()) {
                Text(text = artistName, fontSize = 12.sp, color = TextSecondary)
            }
            Spacer(Modifier.height(10.dp))
            when (genreUiState) {
                is GenreUiState.Detecting    -> DetectingChip()
                is GenreUiState.Detected     -> GenreChip(genre = genreUiState.genre)
                is GenreUiState.DetectionOff -> DetectionOffChip()
                is GenreUiState.Error        -> ErrorChip()
                is GenreUiState.Idle         -> {}
            }
        }
    }
}

@Composable
private fun GenreChip(genre: String) {
    AnimatedVisibility(visible = true, enter = fadeIn(), exit = fadeOut()) {
        Surface(shape = RoundedCornerShape(20.dp), color = AccentPurple.copy(alpha = 0.15f)) {
            Text(
                text     = "● $genre",
                fontSize = 10.sp,
                color    = AccentPurpleLight,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun DetectingChip() {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue  = 0.3f,
        targetValue   = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label         = "alpha"
    )
    Surface(shape = RoundedCornerShape(20.dp), color = TextSecondary.copy(alpha = 0.1f)) {
        Text(
            text     = "◌ Detecting genre…",
            fontSize = 10.sp,
            color    = TextSecondary,
            modifier = Modifier.alpha(alpha).padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun DetectionOffChip() {
    Surface(shape = RoundedCornerShape(20.dp), color = TextSecondary.copy(alpha = 0.1f)) {
        Text(
            text     = "◌ Auto-detect off",
            fontSize = 10.sp,
            color    = TextSecondary,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun ErrorChip() {
    Surface(shape = RoundedCornerShape(20.dp), color = Color(0x22FF5252)) {
        Text(
            text     = "⚠ Genre detection failed",
            fontSize = 10.sp,
            color    = Color(0xFFFF5252),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

// ── EQ Status card ────────────────────────────────────────────────────

@Composable
fun EqStatusCard(
    enabled: Boolean,
    activePreset: String,
    serviceRunning: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape  = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Surface2Dark)
    ) {
        Row(
            modifier              = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text          = "AUTO EQ",
                    fontSize      = 10.sp,
                    color         = TextSecondary,
                    letterSpacing = 1.5.sp
                )
                Text(
                    text       = when {
                        !serviceRunning -> "Engine starting…"
                        enabled         -> activePreset.ifBlank { "Active" }
                        else            -> "Paused"
                    },
                    fontSize   = 12.sp,
                    color      = if (enabled && serviceRunning) AccentPurpleLight else TextSecondary,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Switch(
                checked         = enabled,
                onCheckedChange = onToggle,
                colors          = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = AccentPurple
                )
            )
        }
    }
}

// ── Headphone card ────────────────────────────────────────────────────

@Composable
fun HeadphoneCard(headphoneName: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable { onClick() },
        shape  = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Surface2Dark)
    ) {
        Row(
            modifier          = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("🎧", fontSize = 28.sp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text       = headphoneName,
                    fontSize   = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color      = TextPrimary
                )
                Text(text = "AutoEQ profile loaded", fontSize = 10.sp, color = TextSecondary)
            }
            Text(
                text       = "Change",
                color      = AccentPurpleLight,
                fontSize   = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
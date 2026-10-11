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
    val correctionStatus     by EqState.headphoneCorrectionStatus.collectAsState()
    val wiredConnected       by BluetoothHeadphoneDetector.wiredConnected.collectAsState()
    val testToneMessage      by viewModel.testToneMessage.collectAsState()
    val genreMix             by viewModel.genreMix.collectAsState()
    val recentTracks         by viewModel.recentTracks.collectAsState()
    val correctionMessage    by viewModel.correctionMessage.collectAsState()
    var showRecent by remember { mutableStateOf(false) }
    var correctionTarget by remember { mutableStateOf<Pair<String, String>?>(null) }

    correctionTarget?.let { (title, artist) ->
        GenreCorrectionDialog(title, artist, viewModel.correctionGenres,
            onSelect = { genre -> viewModel.correctGenre(title, artist, genre); correctionTarget = null },
            onDismiss = { correctionTarget = null })
    }
    if (showRecent) {
        RecentTracksDialog(recentTracks, onCorrect = { title, artist ->
            showRecent = false
            correctionTarget = title to artist
        }, onDismiss = { showRecent = false })
    }
    correctionMessage?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissCorrectionMessage,
            title = { Text("Genre correction") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = viewModel::dismissCorrectionMessage) { Text("OK") } }
        )
    }

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
                genreUiState = genreUiState,
                onCorrect = { correctionTarget = trackName to artistName }
            )
        }

        GenreMixCard(genreMix, onRecent = { showRecent = true })

        EqStatusCard(
            enabled        = isEqEnabled,
            activePreset   = activePreset,
            serviceRunning = serviceRunning,
            testToneMessage = testToneMessage,
            onToggle       = { viewModel.setEqEnabled(it) },
            onTestEq       = viewModel::playEqTestTone
        )

        HeadphoneCard(
            headphoneName = selectedHeadphone,
            correctionStatus = correctionStatus,
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
            AppGlyph("Headphones")
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
            AppGlyph("Settings")
        }
    }
}

// ── Now Playing card ──────────────────────────────────────────────────

@Composable
fun NowPlayingCard(
    trackName: String, artistName: String, genreUiState: GenreUiState,
    onCorrect: () -> Unit = {}
) {
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
                is GenreUiState.Detected     -> GenreChip(state = genreUiState)
                is GenreUiState.DetectionOff -> DetectionOffChip()
                is GenreUiState.Error        -> ErrorChip()
                is GenreUiState.Idle         -> {}
            }
            if (artistName.isNotBlank()) {
                TextButton(onClick = onCorrect) { Text("Change genre", color = AccentPurpleLight) }
            }
        }
    }
}

@Composable
private fun GenreMixCard(mix: GenreMix, onRecent: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Surface2Dark)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("GENRE MIX FROM DETECTED TRACKS", fontSize = 10.sp, color = NeonCyan)
            Text("Past 7 days • ${mix.total} track observations (not listening time)",
                fontSize = 11.sp, color = TextSecondary)
            Text("Only tracks detected while the listener and Auto-Genre were active are counted.",
                fontSize = 10.sp, color = TextSecondary)
            if (mix.total == 0) {
                Text("No tracks observed yet. Enable notification access and Media Listener to start.",
                    fontSize = 12.sp, color = TextSecondary, modifier = Modifier.padding(vertical = 12.dp))
            } else {
                mix.items.forEach { item ->
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(item.genre, color = TextPrimary, fontSize = 12.sp)
                        Text("${item.percent}% (${item.count})", color = AccentPurpleLight, fontSize = 12.sp)
                    }
                    LinearProgressIndicator(
                        progress = { item.count.toFloat() / mix.total },
                        modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
                        color = AccentPurple,
                        trackColor = BgDark
                    )
                }
            }
            TextButton(onClick = onRecent) { Text("Recent tracks", color = AccentPurpleLight) }
        }
    }
}

@Composable
private fun RecentTracksDialog(
    tracks: List<RecentGenreTrack>, onCorrect: (String, String) -> Unit, onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Recent tracks") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text("Last 14 days • up to 15 distinct tracks", fontSize = 11.sp)
                if (tracks.isEmpty()) Text("No tracks observed yet.")
                tracks.forEach { track ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(track.title, fontSize = 12.sp)
                            Text("${track.artist} • ${track.genre}", fontSize = 10.sp)
                        }
                        TextButton(onClick = { onCorrect(track.title, track.artist) }) { Text("Change") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun GenreCorrectionDialog(
    title: String, artist: String, genres: List<String>,
    onSelect: (String?) -> Unit, onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Genre for $title") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(artist, fontSize = 12.sp)
                TextButton(onClick = { onSelect(null) }) { Text("Use automatic detection") }
                genres.forEach { genre ->
                    TextButton(onClick = { onSelect(genre) }) { Text(genre) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun GenreChip(state: GenreUiState.Detected) {
    AnimatedVisibility(visible = true, enter = fadeIn(), exit = fadeOut()) {
        Surface(shape = RoundedCornerShape(20.dp), color = AccentPurple.copy(alpha = 0.15f)) {
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)) {
                Text(
                    text = "● ${state.genre}",
                    fontSize = 10.sp,
                    color = AccentPurpleLight
                )
                Text(
                    text = if (state.source == "Manual correction") "Source: Manual correction"
                        else "Source: ${state.source} • Confidence: ${state.confidence}" +
                            if (state.isFallback) " • fallback" else "",
                    fontSize = 9.sp,
                    color = TextSecondary
                )
            }
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
    testToneMessage: String?,
    onToggle: (Boolean) -> Unit,
    onTestEq: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape  = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Surface2Dark)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
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
                    checked = enabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = AccentPurple
                    )
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onTestEq,
                enabled = serviceRunning && testToneMessage != "Playing test sound...",
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    testToneMessage ?: "Test EQ",
                    fontSize = 11.sp,
                    color = if (serviceRunning) AccentPurpleLight else TextSecondary
                )
            }
        }
    }
}

// ── Headphone card ────────────────────────────────────────────────────

@Composable
fun HeadphoneCard(
    headphoneName: String,
    correctionStatus: HeadphoneCorrectionStatus,
    onClick: () -> Unit
) {
    val statusText = when (correctionStatus) {
        is HeadphoneCorrectionStatus.Loading -> "Downloading gain values..."
        is HeadphoneCorrectionStatus.Downloaded -> "Gain values downloaded successfully"
        is HeadphoneCorrectionStatus.Cached -> "Using saved gain values"
        is HeadphoneCorrectionStatus.Failed -> "Error: ${correctionStatus.message}"
        is HeadphoneCorrectionStatus.Imported -> "Using imported profile: ${correctionStatus.displayName}"
        is HeadphoneCorrectionStatus.ImportFailed -> "Error: ${correctionStatus.message}"
        HeadphoneCorrectionStatus.Idle -> null
    }.takeIf {
        when (correctionStatus) {
            is HeadphoneCorrectionStatus.Loading -> correctionStatus.headphoneName == headphoneName
            is HeadphoneCorrectionStatus.Downloaded -> correctionStatus.headphoneName == headphoneName
            is HeadphoneCorrectionStatus.Cached -> correctionStatus.headphoneName == headphoneName
            is HeadphoneCorrectionStatus.Failed -> correctionStatus.headphoneName == headphoneName
            is HeadphoneCorrectionStatus.Imported,
            is HeadphoneCorrectionStatus.ImportFailed -> true
            HeadphoneCorrectionStatus.Idle -> false
        }
    }

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
            AppGlyph("Headphones", AccentPurpleLight)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text       = headphoneName,
                    fontSize   = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color      = TextPrimary
                )
                Text(text = "Manage headphone correction", fontSize = 12.sp, color = TextSecondary)
                statusText?.let {
                    Text(
                        text = it,
                        fontSize = 10.sp,
                        color = if (correctionStatus is HeadphoneCorrectionStatus.Failed ||
                            correctionStatus is HeadphoneCorrectionStatus.ImportFailed)
                            MaterialTheme.colorScheme.error else NeonCyan
                    )
                }
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

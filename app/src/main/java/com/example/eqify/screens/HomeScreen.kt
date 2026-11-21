package com.example.eqify

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.eqify.ui.components.*
import com.example.eqify.ui.theme.*

/*
 * Home (UI_REDESIGN.md §6 "Home"). UI only: every value and action comes from
 * HomeViewModel, EqState and BluetoothHeadphoneDetector exactly as before.
 * Order: Now Playing hero → Sound (EQ + headphone + bass) → Genre mix.
 */

private const val IDLE_TRACK = "Waiting for music…"
private val BASS_STEPS = listOf(0f, 3f, 6f, 9f)

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
    val bassLevel            by EqState.bassBoostLevel.collectAsState()
    val wiredConnected       by BluetoothHeadphoneDetector.wiredConnected.collectAsState()
    val testToneMessage      by viewModel.testToneMessage.collectAsState()
    val genreMix             by viewModel.genreMix.collectAsState()
    val recentTracks         by viewModel.recentTracks.collectAsState()
    val correctionMessage    by viewModel.correctionMessage.collectAsState()
    var showRecent by remember { mutableStateOf(false) }
    var correctionTarget by remember { mutableStateOf<Pair<String, String>?>(null) }

    correctionTarget?.let { (title, artist) ->
        GenreCorrectionSheet(title, artist, viewModel.correctionGenres,
            onSelect = { genre -> viewModel.correctGenre(title, artist, genre); correctionTarget = null },
            onDismiss = { correctionTarget = null })
    }
    if (showRecent) {
        RecentTracksSheet(recentTracks, onCorrect = { title, artist ->
            showRecent = false
            correctionTarget = title to artist
        }, onDismiss = { showRecent = false })
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(EqifyTheme.colors.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = EqSpacing.screen)
            .padding(bottom = EqSpacing.l),
        verticalArrangement = Arrangement.spacedBy(EqSpacing.cardGap)
    ) {
        TopBar(onSettingsClick = onNavigateToSettings)

        if (wiredConnected) {
            StatusBanner(
                message = "Wired headphones detected. Is your headphone selection still correct?",
                kind = StatusKind.ACTIVE,
                actionLabel = "Change",
                onAction = {
                    BluetoothHeadphoneDetector.dismissWiredBanner()
                    onNavigateToHeadphones()
                },
                onDismiss = { BluetoothHeadphoneDetector.dismissWiredBanner() }
            )
        }
        correctionMessage?.let {
            StatusBanner(it, StatusKind.ERROR, onDismiss = viewModel::dismissCorrectionMessage)
        }

        NowPlayingCard(
            enabled = mediaListenerEnabled,
            trackName = trackName,
            artistName = artistName,
            genreUiState = genreUiState,
            onCorrect = { correctionTarget = trackName to artistName },
            onOpenSettings = onNavigateToSettings
        )

        SoundCard(
            eqEnabled = isEqEnabled,
            activePreset = activePreset,
            serviceRunning = serviceRunning,
            onToggleEq = { viewModel.setEqEnabled(it) },
            headphoneName = selectedHeadphone,
            correctionStatus = correctionStatus,
            onHeadphoneClick = onNavigateToHeadphones,
            bassLevel = bassLevel,
            onBassChange = { viewModel.setBassBoost(it) },
            testToneMessage = testToneMessage,
            onTestEq = viewModel::playEqTestTone
        )

        GenreMixCard(genreMix, onRecent = { showRecent = true })
    }
}

// ── Top bar ───────────────────────────────────────────────────────────

@Composable
private fun TopBar(onSettingsClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = EqSpacing.m),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("EQify", style = MaterialTheme.typography.headlineSmall, color = EqifyTheme.colors.textPrimary,
            modifier = Modifier.weight(1f))
        IconAction(EqIcons.Settings, "Settings", onSettingsClick)
    }
}

// ── Now Playing hero ──────────────────────────────────────────────────

@Composable
private fun NowPlayingCard(
    enabled: Boolean,
    trackName: String,
    artistName: String,
    genreUiState: GenreUiState,
    onCorrect: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val c = EqifyTheme.colors
    EqCard(modifier = Modifier.animateContentSize()) {
        SectionHeader("Now playing", accent = true, modifier = Modifier.padding(bottom = EqSpacing.xs))
        when {
            !enabled -> EmptyState(
                icon = EqIcons.Music,
                title = "Song detection is off",
                body = "Turn on the media listener to switch EQ automatically by genre.",
                actionLabel = "Open settings",
                onAction = onOpenSettings,
                modifier = Modifier.padding(0.dp)
            )
            trackName == IDLE_TRACK || artistName.isBlank() -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(EqSpacing.m),
                modifier = Modifier.padding(vertical = EqSpacing.s)
            ) {
                Box(Modifier.size(44.dp).clip(PillShape).background(c.surfaceHighest), contentAlignment = Alignment.Center) {
                    EqIcon(EqIcons.Music, null)
                }
                Column {
                    Text("Waiting for music", style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                    Text("Play something in Spotify, YouTube Music, YouTube or Apple Music.",
                        style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                }
            }
            else -> {
                Text(trackName, style = MaterialTheme.typography.titleLarge, color = c.textPrimary,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(artistName, style = MaterialTheme.typography.bodyMedium, color = c.textMuted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(EqSpacing.m))
                EqCrossfade(genreUiState) { state -> GenreStatus(state) }
                Spacer(Modifier.height(EqSpacing.s))
                // Make correcting prominent when the result is uncertain (PRD §6.3 affordance).
                val uncertain = genreUiState is GenreUiState.Error ||
                    (genreUiState is GenreUiState.Detected &&
                        ((genreUiState as GenreUiState.Detected).isFallback ||
                            (genreUiState as GenreUiState.Detected).confidence.equals("Low", ignoreCase = true)))
                if (uncertain) {
                    TonalButton("Wrong genre? Fix it", onCorrect, icon = EqIcons.Edit)
                } else {
                    TextAction("Change genre", onCorrect, icon = EqIcons.Edit)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GenreStatus(state: GenreUiState) {
    when (state) {
        is GenreUiState.Detected -> Column(verticalArrangement = Arrangement.spacedBy(EqSpacing.xs)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(EqSpacing.s),
                verticalArrangement = Arrangement.spacedBy(EqSpacing.xs)) {
                PillChip(state.genre, selected = true, leadingIcon = EqIcons.GraphicEq)
                if (state.source == "Manual correction") {
                    StatusChip("Your correction", StatusKind.ACTIVE)
                } else {
                    val kind = when {
                        state.isFallback -> StatusKind.WARNING
                        state.confidence.equals("High", true) -> StatusKind.SUCCESS
                        state.confidence.equals("Low", true) -> StatusKind.WARNING
                        else -> StatusKind.ACTIVE
                    }
                    StatusChip("${state.confidence} confidence", kind)
                }
            }
            if (state.source != "Manual correction") {
                Text("via ${state.source}" + if (state.isFallback) " · fallback result" else "",
                    style = MaterialTheme.typography.bodySmall, color = EqifyTheme.colors.textMuted)
            }
        }
        is GenreUiState.Detecting -> {
            val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
                initialValue = 0.4f, targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "alpha"
            )
            StatusChip("Detecting genre…", StatusKind.NEUTRAL, Modifier.alpha(pulse))
        }
        is GenreUiState.DetectionOff -> StatusChip("Auto-genre off", StatusKind.NEUTRAL)
        is GenreUiState.Error -> StatusChip("Genre detection failed", StatusKind.ERROR)
        is GenreUiState.Idle -> {}
    }
}

// ── Sound: EQ master, headphone correction, bass ──────────────────────

@Composable
private fun SoundCard(
    eqEnabled: Boolean,
    activePreset: String,
    serviceRunning: Boolean,
    onToggleEq: (Boolean) -> Unit,
    headphoneName: String,
    correctionStatus: HeadphoneCorrectionStatus,
    onHeadphoneClick: () -> Unit,
    bassLevel: Float,
    onBassChange: (Float) -> Unit,
    testToneMessage: String?,
    onTestEq: () -> Unit
) {
    val c = EqifyTheme.colors
    EqCard(title = "Sound", contentPadding = PaddingValues(bottom = EqSpacing.s)) {
        SwitchRow(
            title = "Auto EQ",
            subtitle = when {
                !serviceRunning -> "Engine starting…"
                eqEnabled -> "Active · ${activePreset.ifBlank { "Flat" }}"
                else -> "Paused · audio is unmodified"
            },
            checked = eqEnabled,
            onCheckedChange = onToggleEq,
            icon = EqIcons.Equalizer
        )
        HorizontalDivider(color = c.outline, modifier = Modifier.padding(horizontal = EqSpacing.l))

        val status = headphoneStatus(correctionStatus, headphoneName)
        SettingRow(
            title = headphoneName,
            subtitle = status?.detail ?: "Headphone correction",
            icon = EqIcons.Headphones,
            onClick = onHeadphoneClick,
            modifier = Modifier.semantics { contentDescription = "Headphones: $headphoneName. Change headphones" },
            trailing = { status?.let { StatusChip(it.label, it.kind) } ?: EqIcon(EqIcons.Expand, null, size = 20.dp) }
        )
        HorizontalDivider(color = c.outline, modifier = Modifier.padding(horizontal = EqSpacing.l))

        Column(Modifier.padding(horizontal = EqSpacing.l, vertical = EqSpacing.m),
            verticalArrangement = Arrangement.spacedBy(EqSpacing.s)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Bass boost", style = MaterialTheme.typography.titleSmall, color = c.textPrimary,
                    modifier = Modifier.weight(1f))
                Text(if (bassLevel == 0f) "Off" else "+${bassLevel.toInt()} dB sub-bass",
                    style = MaterialTheme.typography.labelMedium.tabular(),
                    color = if (bassLevel > 0f) c.accent else c.textMuted)
            }
            SegmentedToggle(
                options = BASS_STEPS,
                // An unexpected stored level (not one of the steps) shows no segment selected, as before.
                selected = bassLevel,
                onSelect = onBassChange,
                label = { if (it == 0f) "Off" else "+${it.toInt()}" },
                modifier = Modifier.fillMaxWidth()
            )
        }
        TextAction(
            text = testToneMessage ?: "Play test sound",
            onClick = onTestEq,
            enabled = serviceRunning && testToneMessage != "Playing test sound...",
            icon = EqIcons.Play,
            modifier = Modifier.padding(horizontal = EqSpacing.s)
        )
    }
}

private data class HeadphoneStatusUi(val label: String, val kind: StatusKind, val detail: String?)

/** Same visibility rules as before: dataset statuses only for the selected name. */
private fun headphoneStatus(status: HeadphoneCorrectionStatus, headphoneName: String): HeadphoneStatusUi? = when (status) {
    is HeadphoneCorrectionStatus.Loading ->
        if (status.headphoneName == headphoneName) HeadphoneStatusUi("Downloading", StatusKind.NEUTRAL, "Downloading gain values…") else null
    is HeadphoneCorrectionStatus.Downloaded ->
        if (status.headphoneName == headphoneName) HeadphoneStatusUi("Downloaded", StatusKind.SUCCESS, "Correction downloaded") else null
    is HeadphoneCorrectionStatus.Cached ->
        if (status.headphoneName == headphoneName) HeadphoneStatusUi("Saved", StatusKind.SUCCESS, "Using saved gain values") else null
    is HeadphoneCorrectionStatus.Failed ->
        if (status.headphoneName == headphoneName) HeadphoneStatusUi("Failed", StatusKind.ERROR, status.message) else null
    is HeadphoneCorrectionStatus.Imported -> HeadphoneStatusUi("Imported", StatusKind.ACTIVE, "Using imported profile: ${status.displayName}")
    is HeadphoneCorrectionStatus.ImportFailed -> HeadphoneStatusUi("Failed", StatusKind.ERROR, status.message)
    HeadphoneCorrectionStatus.Idle -> null
}

// ── Genre mix ─────────────────────────────────────────────────────────

@Composable
private fun GenreMixCard(mix: GenreMix, onRecent: () -> Unit) {
    val c = EqifyTheme.colors
    EqCard(title = "Genre mix", action = { TextAction("Recent tracks", onRecent) }) {
        Text("Past 7 days · ${mix.total} detected track${if (mix.total == 1) "" else "s"} (not listening time)",
            style = MaterialTheme.typography.bodySmall, color = c.textMuted)
        Spacer(Modifier.height(EqSpacing.m))
        if (mix.total == 0) {
            Text("No tracks observed yet. Turn on notification access and the media listener to start.",
                style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
        } else {
            val colors = mix.items.mapIndexed { i, item ->
                if (item.genre.equals("Other", ignoreCase = true)) c.textFaint else c.chart[i % c.chart.size]
            }
            // One stacked bar; each segment proportional to its observation count.
            Row(
                Modifier.fillMaxWidth().height(12.dp).clip(PillShape).background(c.surfaceHighest)
                    .semantics { contentDescription = mix.items.joinToString { "${it.genre} ${it.percent} percent" } },
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                mix.items.forEachIndexed { i, item ->
                    if (item.count > 0) Box(Modifier.weight(item.count.toFloat()).fillMaxHeight().background(colors[i]))
                }
            }
            Spacer(Modifier.height(EqSpacing.m))
            mix.items.forEachIndexed { i, item ->
                Row(Modifier.fillMaxWidth().padding(vertical = EqSpacing.xs), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(PillShape).background(colors[i]))
                    Spacer(Modifier.width(EqSpacing.s))
                    Text(item.genre, style = MaterialTheme.typography.bodyMedium, color = c.textPrimary,
                        modifier = Modifier.weight(1f))
                    ValueReadout(item.percent.toFloat(), "%", decimals = 0)
                    Text("  ·  ${item.count}", style = MaterialTheme.typography.labelMedium.tabular(), color = c.textMuted)
                }
            }
        }
        Spacer(Modifier.height(EqSpacing.s))
        Text("Only tracks detected while the listener and Auto-Genre were active are counted.",
            style = MaterialTheme.typography.bodySmall, color = c.textFaint)
    }
}

// ── Sheets ────────────────────────────────────────────────────────────

@Composable
private fun RecentTracksSheet(
    tracks: List<RecentGenreTrack>, onCorrect: (String, String) -> Unit, onDismiss: () -> Unit
) {
    EqSheet(title = "Recent tracks", subtitle = "Last 14 days · up to 15 distinct tracks", onDismiss = onDismiss) {
        if (tracks.isEmpty()) {
            EmptyState(EqIcons.Music, "No tracks observed yet", body = "Detected tracks will appear here.")
        } else {
            EqCard(contentPadding = PaddingValues(0.dp)) {
                tracks.forEachIndexed { i, track ->
                    if (i > 0) HorizontalDivider(color = EqifyTheme.colors.outline)
                    SettingRow(
                        title = track.title,
                        subtitle = "${track.artist} · ${track.genre}",
                        trailing = { TextAction("Change", { onCorrect(track.title, track.artist) }) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GenreCorrectionSheet(
    title: String, artist: String, genres: List<String>,
    onSelect: (String?) -> Unit, onDismiss: () -> Unit
) {
    EqSheet(title = "Genre for \"$title\"", subtitle = artist, onDismiss = onDismiss) {
        EqCard(contentPadding = PaddingValues(0.dp)) {
            SettingRow(
                title = "Use automatic detection",
                subtitle = "Remove your correction for this track",
                icon = EqIcons.Refresh,
                onClick = { onSelect(null) }
            )
        }
        SectionHeader("Choose a genre")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(EqSpacing.s),
            verticalArrangement = Arrangement.spacedBy(EqSpacing.s)) {
            genres.forEach { genre -> PillChip(genre, onClick = { onSelect(genre) }) }
        }
    }
}

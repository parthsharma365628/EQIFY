package com.example.eqify

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
 * HomeViewModel, EqState and HeadphoneDetector.
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
    // Final applied output (tone + headphone correction + bass, after protection); zeros while paused/bypassed.
    val outputGains          by EqState.activeBandGains.collectAsState()
    val isBypassed           by EqState.isBypassed.collectAsState()
    val detectionPrompt      by HeadphoneDetector.prompt.collectAsState()
    val testToneMessage      by viewModel.testToneMessage.collectAsState()
    val genreMix             by viewModel.genreMix.collectAsState()
    val weekStats            by viewModel.weekStats.collectAsState()
    val recentTracks         by viewModel.recentTracks.collectAsState()
    val correctionMessage    by viewModel.correctionMessage.collectAsState()
    var showRecent by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var showTestSound by remember { mutableStateOf(false) }
    val sleepState by viewModel.sleepTimer.collectAsState()
    val playingTone by viewModel.playingTone.collectAsState()
    val forceMono by viewModel.forceMono.collectAsState(initial = false)
    val appliedLoudness by EqState.appliedLoudness.collectAsState()
    var showMix by remember { mutableStateOf(false) }
    var correctionTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    val customPresets by viewModel.customPresetNames.collectAsState()
    val updateBanner by viewModel.updateBanner.collectAsState()
    val deviceSound by viewModel.deviceSound.collectAsState()
    val manualOverride by EqState.manualOverrideActive.collectAsState()
    val effective = deviceSound.effective
    val context = androidx.compose.ui.platform.LocalContext.current

    correctionTarget?.let { (title, artist) ->
        GenreCorrectionSheet(title, artist, viewModel.correctionGenres, customPresets,
            onSelect = { genre -> viewModel.correctGenre(title, artist, genre); correctionTarget = null },
            onDismiss = { correctionTarget = null })
    }
    if (showMix) {
        WeekInSoundSheet(weekStats, onRecent = { showMix = false; showRecent = true }, onDismiss = { showMix = false })
    }
    if (showSleep) {
        SleepTimerSheet(
            state = sleepState,
            endOfTrackAvailable = mediaListenerEnabled,
            onStart = { viewModel.startSleepTimer(it); showSleep = false },
            onCancel = viewModel::cancelSleepTimer,
            onDismiss = { showSleep = false }
        )
    }
    if (showTestSound) {
        TestSoundSheet(playing = playingTone, forceMono = forceMono, enabled = serviceRunning,
            onPlay = viewModel::playTestTone, onDismiss = { showTestSound = false })
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
            .padding(bottom = EqSpacing.l),
        verticalArrangement = Arrangement.spacedBy(EqSpacing.m)
    ) {
        // DESIGN_REVIEW.md item 6: no cards on Home. Rows run full width with
        // hairline dividers; everything else sits in the screen gutter.
        val gutter = Modifier.padding(horizontal = EqSpacing.screen)
        TopBar(onSettingsClick = onNavigateToSettings, onSleepClick = { showSleep = true },
            sleepActive = sleepState != SleepTimer.State.Off, modifier = gutter)

        detectionPrompt?.let { prompt ->
            DetectionBanner(prompt, onChoose = {
                HeadphoneDetector.dismissPrompt()
                onNavigateToHeadphones()
            }, modifier = gutter)
        }
        correctionMessage?.let {
            StatusBanner(it, StatusKind.ERROR, gutter, onDismiss = viewModel::dismissCorrectionMessage)
        }
        updateBanner?.let { release ->
            StatusBanner(
                "EQify ${release.version} is available.", StatusKind.ACTIVE, gutter,
                actionLabel = "What's new",
                onAction = {
                    runCatching {
                        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(release.url)))
                    }
                },
                onDismiss = { viewModel.dismissUpdate(release.tag) }
            )
        }

        NowPlayingCard(
            enabled = mediaListenerEnabled,
            trackName = trackName,
            artistName = artistName,
            genreUiState = genreUiState,
            onCorrect = { correctionTarget = trackName to artistName },
            onOpenSettings = onNavigateToSettings,
            modifier = gutter
        )

        LiveCurve(gains = outputGains.toList(), active = isEqEnabled && !isBypassed && serviceRunning, modifier = gutter)

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
            onTestEq = { showTestSound = true },
            loudnessPeak = LoudnessCompensation.peak(appliedLoudness),
            // Only while automatic genre EQ is what's playing; a manual preset ignores it.
            showGenreStrength = effective.autoGenre && effective.devicePreset == null && !manualOverride && isEqEnabled,
            genreStrength = effective.genreStrength,
            onGenreStrengthChange = viewModel::setGenreStrength,
            strengthScope = deviceSound.label.takeIf { effective.strengthFromDevice },
            bassScope = deviceSound.label.takeIf { effective.bassFromDevice }
        )

        GenreMixRow(genreMix, onClick = { showMix = true })
    }
}

// ── Headphone detection (FEATURE_HEADPHONE_DETECTION.md) ──────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetectionBanner(prompt: HeadphoneDetector.Prompt, onChoose: () -> Unit, modifier: Modifier = Modifier) {
    val c = EqifyTheme.colors
    val device = prompt.output.displayName
    val message = when (prompt) {
        is HeadphoneDetector.Prompt.AutoSelected -> "Using the ${prompt.model} correction for $device."
        is HeadphoneDetector.Prompt.Suggest -> "Is $device a ${prompt.model}?"
        is HeadphoneDetector.Prompt.Unknown ->
            if (prompt.output.kind == AudioOutputKind.WIRED) "Wired headphones connected. Which model are they?"
            else "Which headphones are $device?"
    }
    Column(
        modifier.fillMaxWidth().clip(EqifyShapes.medium).background(c.accentContainer)
            .padding(start = EqSpacing.m, end = EqSpacing.xs, top = EqSpacing.s, bottom = EqSpacing.xs)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(EqSpacing.m)) {
            EqIcon(EqIcons.Headphones, contentDescription = null, tint = c.onAccentContainer, size = 20.dp)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = c.onAccentContainer,
                modifier = Modifier.weight(1f))
            IconAction(EqIcons.Close, "Dismiss", { HeadphoneDetector.dismissPrompt() }, tint = c.onAccentContainer)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(EqSpacing.xs)) {
            when (prompt) {
                is HeadphoneDetector.Prompt.AutoSelected -> {
                    TextAction("Undo", { HeadphoneDetector.undo(prompt) })
                    TextAction("Choose another", onChoose)
                }
                is HeadphoneDetector.Prompt.Suggest -> {
                    TextAction("Use it", { HeadphoneDetector.useSuggestion(prompt) })
                    TextAction("Choose…", onChoose)
                    TextAction("Not headphones", { HeadphoneDetector.markNotHeadphones(prompt.output) })
                }
                is HeadphoneDetector.Prompt.Unknown -> {
                    TextAction("Choose…", onChoose)
                    if (prompt.output.kind != AudioOutputKind.WIRED)
                        TextAction("Not headphones", { HeadphoneDetector.markNotHeadphones(prompt.output) })
                }
            }
        }
    }
}

// ── Top bar ───────────────────────────────────────────────────────────

@Composable
private fun TopBar(onSettingsClick: () -> Unit, onSleepClick: () -> Unit, sleepActive: Boolean,
                   modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(top = EqSpacing.m),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("EQify", style = MaterialTheme.typography.headlineSmall, color = EqifyTheme.colors.textPrimary,
            modifier = Modifier.weight(1f))
        IconAction(EqIcons.Bedtime, if (sleepActive) "Sleep timer, running" else "Sleep timer", onSleepClick,
            tint = if (sleepActive) EqifyTheme.colors.accent else EqifyTheme.colors.textMuted)
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
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = EqifyTheme.colors
    Column(modifier.fillMaxWidth().animateContentSize()) {
        when {
            !enabled -> EmptyState(
                icon = EqIcons.Music,
                title = "Song detection is off",
                body = "Turn on song detection to switch EQ by genre automatically.",
                actionLabel = "Open settings",
                onAction = onOpenSettings
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
                PillChip(GenreChoice.shortLabel(state.genre), selected = true,
                    leadingIcon = if (GenreChoice.isPreset(state.genre)) EqIcons.Tune else EqIcons.GraphicEq)
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
                Text("Found via ${state.source}" + if (state.isFallback) ", as a fallback" else "",
                    style = MaterialTheme.typography.bodySmall, color = EqifyTheme.colors.textMuted)
            }
        }
        is GenreUiState.Detecting -> {
            val reduceMotion = rememberReduceMotion()
            val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
                initialValue = 0.4f, targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "alpha"
            )
            StatusChip("Detecting genre…", StatusKind.NEUTRAL, Modifier.alpha(if (reduceMotion) 1f else pulse))
        }
        is GenreUiState.DetectionOff -> StatusChip("Auto-genre off", StatusKind.NEUTRAL)
        is GenreUiState.Error -> StatusChip("Genre detection failed", StatusKind.ERROR)
        is GenreUiState.Idle -> {}
    }
}

// ── Live curve (DESIGN_REVIEW.md item 1) ──────────────────────────────
//
// The app's signature: what is shaping the sound right now. No card, no grid,
// no numbers above it; the band labels are the only scale. It morphs only when
// the applied output changes (never on first load) and snaps when the system
// "remove animations" setting is on.

@Composable
private fun LiveCurve(gains: List<Float>, active: Boolean, modifier: Modifier = Modifier) {
    val c = EqifyTheme.colors
    val reduceMotion = rememberReduceMotion()
    val shown = List(EqProfileManager.BAND_CENTERS_HZ.size) { i ->
        animateFloatAsState(gains.getOrElse(i) { 0f }, changeMotion(reduceMotion), label = "live-band-$i").value
    }
    val alpha by animateFloatAsState(if (active) 1f else 0.4f, changeMotion(reduceMotion, 250), label = "live-alpha")
    val description = if (!active) "EQ is off, so the sound is unchanged."
        else if (gains.all { kotlin.math.abs(it) < 0.05f }) "Current sound: flat, no EQ applied."
        else "Current sound: " + EqProfileManager.BAND_LABELS.indices.joinToString(", ") { i ->
            "${EqProfileManager.BAND_LABELS[i]} ${formatValue(gains.getOrElse(i) { 0f }, "dB", signed = true)}"
        } + "."
    Column(modifier.fillMaxWidth().padding(vertical = EqSpacing.s)) {
        ToneCurve(
            gains = shown,
            height = 112.dp,
            showGrid = false,
            showPoints = false,
            contentDescription = description,
            modifier = Modifier.graphicsLayer { this.alpha = alpha }
        )
        Row(Modifier.fillMaxWidth().padding(top = EqSpacing.xs)) {
            EqProfileManager.BAND_LABELS.forEach { label ->
                Text(label.removeSuffix("Hz"), style = MaterialTheme.typography.labelSmall.condensed(), color = c.textFaint,
                    textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.weight(1f))
            }
        }
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
    loudnessPeak: Float = 0f,
    onTestEq: () -> Unit,
    showGenreStrength: Boolean = false,
    genreStrength: Float = GenreStrength.DEFAULT,
    onGenreStrengthChange: (Float) -> Unit = {},
    /** Set when the value belongs to the connected device (FEATURES_NEXT_PLAN.md §4). */
    strengthScope: String? = null,
    bassScope: String? = null
) {
    val c = EqifyTheme.colors
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(color = c.outline)
        SwitchRow(
            title = "Auto EQ",
            subtitle = when {
                !serviceRunning -> "Engine starting…"
                eqEnabled -> "On, using the ${activePreset.ifBlank { "Flat" }} preset"
                else -> "Paused. Audio plays unchanged."
            },
            checked = eqEnabled,
            onCheckedChange = onToggleEq,
            icon = EqIcons.Equalizer
        )
        HorizontalDivider(color = c.outline, modifier = Modifier.padding(start = 56.dp))

        val status = headphoneStatus(correctionStatus, headphoneName)
        SettingRow(
            title = headphoneName,
            subtitle = status?.detail ?: "Headphone correction",
            icon = EqIcons.Headphones,
            onClick = onHeadphoneClick,
            modifier = Modifier.semantics { contentDescription = "Headphones: $headphoneName. Change headphones" },
            trailing = { status?.let { StatusChip(it.label, it.kind) } ?: EqIcon(EqIcons.Expand, null, size = 20.dp) }
        )
        HorizontalDivider(color = c.outline, modifier = Modifier.padding(start = 56.dp))

        Column(Modifier.padding(horizontal = EqSpacing.l, vertical = EqSpacing.m),
            verticalArrangement = Arrangement.spacedBy(EqSpacing.s)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Bass boost", style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                    bassScope?.let {
                        Text("For $it", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                    }
                }
                Text(if (bassLevel == 0f) "Off" else "+${bassLevel.toInt()} dB sub-bass",
                    style = MaterialTheme.typography.labelMedium.condensed().tabular(),
                    color = if (bassLevel > 0f) c.accent else c.textMuted)
            }
            // Volume-aware bass (FEATURES_BATCH2_PLAN.md §1).
            val appliedBass by EqState.appliedBassBoost.collectAsState()
            if (bassLevel > 0f && appliedBass < bassLevel) {
                Text("Eased to +${"%.1f".format(appliedBass)} dB at this volume",
                    style = MaterialTheme.typography.bodySmall, color = c.textMuted)
            }
            // Loudness compensation (FEATURES_BATCH3_PLAN.md §B).
            if (eqEnabled && loudnessPeak > 0f) {
                Text("Loudness compensation +${"%.1f".format(loudnessPeak)} dB at this volume",
                    style = MaterialTheme.typography.bodySmall, color = c.textMuted)
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
        if (showGenreStrength) {
            HorizontalDivider(color = c.outline, modifier = Modifier.padding(start = EqSpacing.l))
            GenreStrengthControl(genreStrength, onGenreStrengthChange, strengthScope)
        }
        TextAction(
            text = testToneMessage ?: "Test sound",
            onClick = onTestEq,
            enabled = serviceRunning,
            icon = EqIcons.Play,
            modifier = Modifier.padding(horizontal = EqSpacing.s)
        )
        HorizontalDivider(color = c.outline)
    }
}

/** Drag locally; the value is saved once, when the finger lifts. */
@Composable
private fun GenreStrengthControl(value: Float, onChange: (Float) -> Unit, scope: String?) {
    val c = EqifyTheme.colors
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = dragging ?: value
    Column(Modifier.padding(horizontal = EqSpacing.l, vertical = EqSpacing.m),
        verticalArrangement = Arrangement.spacedBy(EqSpacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Genre strength", style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                Text(scope?.let { "For $it" } ?: "How strongly genre EQ shapes the sound",
                    style = MaterialTheme.typography.bodySmall, color = c.textMuted)
            }
            Text(GenreStrength.label(shown), style = MaterialTheme.typography.labelMedium.condensed().tabular(),
                color = if (GenreStrength.normalise(shown) == GenreStrength.DEFAULT) c.textMuted else c.accent)
        }
        Slider(
            value = shown,
            onValueChange = { dragging = GenreStrength.normalise(it) },
            onValueChangeFinished = { dragging?.let(onChange); dragging = null },
            valueRange = GenreStrength.MIN..GenreStrength.MAX,
            steps = Math.round((GenreStrength.MAX - GenreStrength.MIN) / GenreStrength.STEP) - 1,
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = "Genre strength ${Math.round(GenreStrength.normalise(shown) * 100)} percent"
            }
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
    is HeadphoneCorrectionStatus.Imported -> HeadphoneStatusUi("Imported", StatusKind.ACTIVE, "Using imported correction: ${status.displayName}")
    is HeadphoneCorrectionStatus.ImportFailed -> HeadphoneStatusUi("Failed", StatusKind.ERROR, status.message)
    is HeadphoneCorrectionStatus.NotOnHeadphones ->
        HeadphoneStatusUi("Off", StatusKind.NEUTRAL, "${status.outputName}: no headphone correction")
    HeadphoneCorrectionStatus.Idle -> null
}

// ── Genre mix ─────────────────────────────────────────────────────────

/** One compact line on Home; the full breakdown opens in a sheet. */
@Composable
private fun GenreMixRow(mix: GenreMix, onClick: () -> Unit) {
    val top = mix.items.firstOrNull { !it.genre.equals("Other", ignoreCase = true) }
    val plural = if (mix.total == 1) "" else "s"
    SettingRow(
        title = "Your week in sound",
        subtitle = when {
            mix.total == 0 -> "No tracks detected yet"
            top != null -> "Mostly ${GenreChoice.label(top.genre)}, ${top.percent}% of ${mix.total} detected track$plural"
            else -> "${mix.total} detected track$plural"
        },
        icon = EqIcons.GraphicEq,
        onClick = onClick,
        trailing = { if (mix.total > 0) GenreMixBar(mix, Modifier.width(88.dp)) }
    )
}

/** One stacked bar; each segment proportional to its observation count. */
@Composable
private fun GenreMixBar(mix: GenreMix, modifier: Modifier = Modifier) {
    val c = EqifyTheme.colors
    Row(
        modifier.height(10.dp).clip(PillShape).background(c.surfaceHighest)
            .semantics { contentDescription = mix.items.joinToString { "${it.genre} ${it.percent} percent" } },
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        mix.items.forEachIndexed { i, item ->
            if (item.count > 0) Box(Modifier.weight(item.count.toFloat()).fillMaxHeight().background(mixColor(i, item.genre)))
        }
    }
}

@Composable
private fun mixColor(index: Int, genre: String) = EqifyTheme.colors.let { c ->
    if (genre.equals("Other", ignoreCase = true)) c.textFaint else c.chart[index % c.chart.size]
}

// ── Sheets ────────────────────────────────────────────────────────────

@Composable
private fun RecentTracksSheet(
    tracks: List<RecentGenreTrack>, onCorrect: (String, String) -> Unit, onDismiss: () -> Unit
) {
    EqSheet(title = "Recent tracks", subtitle = "Up to 15 tracks from the last 14 days", onDismiss = onDismiss) {
        if (tracks.isEmpty()) {
            EmptyState(EqIcons.Music, "No tracks observed yet", body = "Detected tracks will appear here.")
        } else {
            EqCard(contentPadding = PaddingValues(0.dp)) {
                tracks.forEachIndexed { i, track ->
                    if (i > 0) HorizontalDivider(color = EqifyTheme.colors.outline)
                    SettingRow(
                        title = track.title,
                        subtitle = if (GenreChoice.isPreset(track.genre))
                            "${track.artist}\nPreset: ${GenreChoice.shortLabel(track.genre)}"
                        else "${track.artist}\nGenre: ${track.genre}",
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
    title: String, artist: String, genres: List<String>, presets: List<String>,
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
        if (presets.isNotEmpty()) {
            SectionHeader("Your presets")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(EqSpacing.s),
                verticalArrangement = Arrangement.spacedBy(EqSpacing.s)) {
                presets.forEach { name ->
                    PillChip(name, leadingIcon = EqIcons.Tune, onClick = { onSelect(GenreChoice.preset(name)) })
                }
            }
        }
        SectionHeader("Genres")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(EqSpacing.s),
            verticalArrangement = Arrangement.spacedBy(EqSpacing.s)) {
            genres.forEach { genre -> PillChip(genre, onClick = { onSelect(genre) }) }
        }
    }
}

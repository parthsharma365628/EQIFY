package com.example.eqify

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.eqify.ui.components.*
import com.example.eqify.ui.theme.*

/*
 * Settings (UI_REDESIGN.md §6 "Settings"). UI only: every value and action
 * goes through SettingsViewModel exactly as before, including the
 * notification-access check and auto-enable when access is granted.
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit = {},
    vm: SettingsViewModel = viewModel()
) {
    val c = EqifyTheme.colors
    val context       = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // ── Persisted settings (from DataStore via ViewModel) ─────────────
    val isEqEnabled        by vm.isEqEnabled.collectAsState(initial = true)
    val autoGenreDetection by vm.autoGenreDetection.collectAsState(initial = true)
    val smoothEqTransitions by vm.smoothEqTransitions.collectAsState(initial = true)
    val outputProtectionMode by vm.outputProtectionMode.collectAsState(
        initial = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            OutputProtectionMode.BALANCED
        } else {
            OutputProtectionMode.SAFE
        }
    )
    val limiterStatus by vm.limiterDiagnosticStatus.collectAsState()
    val forceMono          by vm.forceMono.collectAsState(initial = false)
    val mediaListenerEnabled  by vm.mediaListenerEnabled.collectAsState(initial = true)
    val headphoneAutoDetect   by vm.headphoneAutoDetect.collectAsState(initial = true)
    val savedLastFmApiKey     by vm.lastFmApiKey.collectAsState(initial = "")
    val cacheMessage          by vm.cacheMessage.collectAsState()
    val historyMessage        by vm.historyMessage.collectAsState()
    val customProfiles by vm.customEqProfiles.collectAsState()
    val customProfilesMessage by vm.customProfilesMessage.collectAsState()
    val genreOverrides by vm.genreOverrides.collectAsState(initial = emptyMap())
    val genreCurvesMessage by vm.genreCurvesMessage.collectAsState()
    val autoUpdateCheck by vm.autoUpdateCheck.collectAsState(initial = true)
    val updateStatus by vm.updateStatus.collectAsState()
    val latestRelease by vm.latestRelease.collectAsState()
    val crashReportingEnabled by vm.crashReportingEnabled.collectAsState()
    val crashReportMessage by vm.crashReportMessage.collectAsState()
    val themeSettings by vm.themeSettings.collectAsState()

    // ── Runtime / permission state ────────────────────────────────────
    var isPermissionGranted by remember { mutableStateOf(isNotificationServiceEnabled(context)) }
    var showPermissionDialog by remember { mutableStateOf(false) }
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var showClearHistoryDialog by remember { mutableStateOf(false) }
    var showClearCustomProfilesDialog by remember { mutableStateOf(false) }
    var showResetGenreCurvesDialog by remember { mutableStateOf(false) }
    var showOutputProtectionSheet by remember { mutableStateOf(false) }
    var showLastFmSheet by remember { mutableStateOf(false) }
    var showPrivacySheet by remember { mutableStateOf(false) }
    var showLicencesSheet by remember { mutableStateOf(false) }

    // Refresh permission state when user returns from the system settings screen
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val grantedNow = isNotificationServiceEnabled(context)
                if (grantedNow && !isPermissionGranted) {
                    // Permission was just granted — enable the feature automatically
                    vm.setMediaListenerEnabled(true)
                }
                isPermissionGranted = grantedNow
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ── Dialogs and sheets ────────────────────────────────────────────
    if (showPermissionDialog) {
        ConfirmDialog(
            title = "Notification access needed",
            message = NotificationAccessInfo.dialogText(
                "To detect music automatically, EQify needs Notification Access. " +
                    "Find EQify on the next screen and turn it on."
            ),
            confirmLabel = "Open settings",
            onConfirm = {
                showPermissionDialog = false
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            },
            onDismiss = { showPermissionDialog = false }
        )
    }
    if (showClearCacheDialog) {
        ConfirmDialog(
            title = "Clear downloaded corrections?",
            message = "Selected AutoEQ corrections will be downloaded again when needed.",
            confirmLabel = "Clear", destructive = true,
            onConfirm = { showClearCacheDialog = false; vm.clearDownloadedCorrections() },
            onDismiss = { showClearCacheDialog = false }
        )
    }
    if (showClearHistoryDialog) {
        ConfirmDialog(
            title = "Clear genre history?",
            message = "Removes local track observations and every saved genre correction. " +
                "Headphone corrections and EQ presets stay intact.",
            confirmLabel = "Clear", destructive = true,
            onConfirm = { showClearHistoryDialog = false; vm.clearGenreHistory() },
            onDismiss = { showClearHistoryDialog = false }
        )
    }
    if (showClearCustomProfilesDialog) {
        ConfirmDialog(
            title = "Clear imported corrections?",
            message = "Deletes every imported correction. If one is in use, no headphone correction is applied.",
            confirmLabel = "Clear", destructive = true,
            onConfirm = { showClearCustomProfilesDialog = false; vm.clearCustomProfiles() },
            onDismiss = { showClearCustomProfilesDialog = false }
        )
    }
    if (showResetGenreCurvesDialog) {
        ConfirmDialog(
            title = "Reset customised genre curves?",
            message = "Every built-in genre you edited (${genreOverrides.keys.sorted().joinToString(", ")}) " +
                "goes back to its default curve. Your own presets stay intact.",
            confirmLabel = "Reset", destructive = true,
            onConfirm = { showResetGenreCurvesDialog = false; vm.resetGenreCurves() },
            onDismiss = { showResetGenreCurvesDialog = false }
        )
    }
    if (showOutputProtectionSheet) {
        OutputProtectionSheet(
            selectedMode = outputProtectionMode,
            limiterStatus = limiterStatus,
            onSelect = { mode ->
                vm.setOutputProtectionMode(mode)
                showOutputProtectionSheet = false
            },
            onDismiss = { showOutputProtectionSheet = false }
        )
    }
    if (showLastFmSheet) {
        LastFmKeySheet(
            savedKey = savedLastFmApiKey,
            onSave = { vm.setLastFmApiKey(it); showLastFmSheet = false },
            onClear = { vm.setLastFmApiKey(""); showLastFmSheet = false },
            onDismiss = { showLastFmSheet = false }
        )
    }
    if (showPrivacySheet) PrivacySheet(onDismiss = { showPrivacySheet = false })
    if (showLicencesSheet) LicencesSheet(onDismiss = { showLicencesSheet = false })

    // ── UI ────────────────────────────────────────────────────────────
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Settings", style = MaterialTheme.typography.titleLarge, color = c.textPrimary) },
                navigationIcon = { IconAction(EqIcons.Back, "Back", onNavigateBack, tint = c.textPrimary) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = c.background)
            )
        },
        containerColor = c.background
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = EqSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(EqSpacing.xs)
        ) {
            // DESIGN_REVIEW.md item 6: no cards. Full-width rows with hairlines,
            // grouped by a sentence-case title; banners sit in the screen gutter.
            val gutter = Modifier.padding(horizontal = EqSpacing.screen)
            // Results of clear actions (previously separate pop-ups)
            cacheMessage?.let { StatusBanner(it, StatusKind.NEUTRAL, gutter, onDismiss = vm::dismissCacheMessage) }
            historyMessage?.let { StatusBanner(it, StatusKind.NEUTRAL, gutter, onDismiss = vm::dismissHistoryMessage) }
            customProfilesMessage?.let { StatusBanner(it, StatusKind.NEUTRAL, gutter, onDismiss = vm::dismissCustomProfilesMessage) }
            genreCurvesMessage?.let { StatusBanner(it, StatusKind.NEUTRAL, gutter, onDismiss = vm::dismissGenreCurvesMessage) }

            Group("Appearance") {
                AppearanceSettings(
                    settings = themeSettings,
                    onMode = vm::setThemeMode,
                    onAccent = vm::setThemeAccent,
                    onDynamic = vm::setDynamicColor,
                    onPureBlack = vm::setPureBlack
                )
            }

            Group("Sound") {
                SwitchRow(
                    title = "Auto EQ",
                    subtitle = if (isEqEnabled) "Applying genre profiles automatically"
                        else "EQ is paused — audio is unmodified",
                    checked = isEqEnabled,
                    onCheckedChange = { vm.setEqEnabled(it) },
                    icon = EqIcons.Equalizer
                )
                RowDivider()
                SwitchRow(
                    title = "Smooth EQ transitions",
                    subtitle = "Gradually shifts EQ when the genre or headphone correction changes",
                    checked = smoothEqTransitions,
                    onCheckedChange = vm::setSmoothEqTransitions,
                    icon = EqIcons.Tune
                )
                RowDivider()
                ValueRow(
                    title = "Output protection",
                    value = outputProtectionMode.label(),
                    subtitle = outputProtectionStatus(outputProtectionMode, limiterStatus),
                    onClick = { showOutputProtectionSheet = true },
                    icon = EqIcons.Warning
                )
                RowDivider()
                SwitchRow(
                    title = "Force mono",
                    subtitle = "Combine left and right channels. Not supported on every phone",
                    checked = forceMono,
                    onCheckedChange = { vm.setForceMono(it) },
                    icon = EqIcons.Headphones
                )
            }

            Group("Detection") {
                SwitchRow(
                    title = "Song detection",
                    subtitle = if (isPermissionGranted) "Reads the song title and artist from supported music apps"
                        else "Notification access required to detect music",
                    checked = mediaListenerEnabled && isPermissionGranted,
                    onCheckedChange = { newValue ->
                        if (newValue) {
                            if (isPermissionGranted) vm.setMediaListenerEnabled(true) else showPermissionDialog = true
                        } else {
                            vm.setMediaListenerEnabled(false)
                        }
                    },
                    icon = EqIcons.Music
                )
                RowDivider()
                SwitchRow(
                    title = "Auto-genre detection",
                    subtitle = if (autoGenreDetection) "EQ profile updates with each track"
                        else "EQ profile stays fixed — switch manually in the EQ tab",
                    checked = autoGenreDetection,
                    onCheckedChange = { vm.setAutoGenreDetection(it) },
                    icon = EqIcons.GraphicEq
                )
                RowDivider()
                SwitchRow(
                    title = "Headphone auto-detection",
                    subtitle = if (headphoneAutoDetect)
                        "Picks the correction for Bluetooth, USB-C and wired headphones, remembers your choices, and turns it off on the phone speaker"
                        else "Headphone selection stays fixed, on every output",
                    checked = headphoneAutoDetect,
                    onCheckedChange = { vm.setHeadphoneAutoDetect(it) },
                    icon = EqIcons.Bluetooth
                )
                RowDivider()
                ValueRow(
                    title = "Last.fm API key",
                    value = if (savedLastFmApiKey.isBlank()) "Not set" else "Set",
                    subtitle = "Optional. Improves genre detection",
                    onClick = { showLastFmSheet = true },
                    icon = EqIcons.Edit
                )
            }

            Group("Data & privacy") {
                SettingRow(
                    title = "About your data",
                    subtitle = "What is sent, what stays on this device",
                    icon = EqIcons.Info,
                    onClick = { showPrivacySheet = true },
                    trailing = { EqIcon(EqIcons.Expand, null, size = 20.dp) }
                )
                RowDivider()
                SettingRow(
                    title = "Imported corrections",
                    subtitle = "GraphicEQ imports stored on this device",
                    icon = EqIcons.GraphicEq,
                    trailing = { ValueReadout(customProfiles.size.toFloat(), "", decimals = 0, color = c.textMuted) }
                )
                RowDivider()
                SettingRow(
                    title = "Clear genre history",
                    subtitle = "Remove local track observations and genre corrections",
                    icon = EqIcons.Delete, destructive = true,
                    onClick = { showClearHistoryDialog = true }
                )
                RowDivider()
                if (genreOverrides.isNotEmpty()) {
                    SettingRow(
                        title = "Reset customised genre curves (${genreOverrides.size})",
                        subtitle = "Return edited built-in genres to their default curves",
                        icon = EqIcons.Reset, destructive = true,
                        onClick = { showResetGenreCurvesDialog = true }
                    )
                    RowDivider()
                }
                SettingRow(
                    title = "Clear downloaded corrections",
                    subtitle = "Remove locally cached AutoEQ curves",
                    icon = EqIcons.Delete, destructive = true,
                    onClick = { showClearCacheDialog = true }
                )
                RowDivider()
                SettingRow(
                    title = "Clear imported corrections",
                    subtitle = "Delete local imports without clearing downloaded corrections",
                    icon = EqIcons.Delete, destructive = true,
                    onClick = { showClearCustomProfilesDialog = true }
                )
            }

            Group("Feedback") {
                SettingRow(
                    title = "Send feedback",
                    subtitle = "Opens your email app — bug reports and ideas welcome",
                    icon = EqIcons.Share,
                    onClick = {
                        val mail = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
                            if (BuildConfig.FEEDBACK_EMAIL.isNotBlank())
                                putExtra(Intent.EXTRA_EMAIL, arrayOf(BuildConfig.FEEDBACK_EMAIL))
                            putExtra(Intent.EXTRA_SUBJECT, "EQify ${BuildConfig.VERSION_NAME} feedback")
                            putExtra(Intent.EXTRA_TEXT, feedbackTemplate())
                        }
                        runCatching { context.startActivity(Intent.createChooser(mail, "Send feedback")) }
                    }
                )
                RowDivider()
                if (vm.crashReportingConfigured) {
                    SwitchRow(
                        title = "Send crash reports",
                        subtitle = "Crash and error details (device model, Android and app version, stack trace) help fix bugs. " +
                            "Song titles, artists, headphone names and keys are removed first.",
                        checked = crashReportingEnabled,
                        onCheckedChange = { vm.setCrashReportingEnabled(it) },
                        icon = EqIcons.Error
                    )
                } else {
                    SettingRow(title = "Crash reports", subtitle = "Not configured in this build",
                        icon = EqIcons.Error, enabled = false)
                }
            }

            Group("About") {
                SettingRow(title = "Version", icon = EqIcons.Info,
                    trailing = { Text(BuildConfig.VERSION_NAME, style = MaterialTheme.typography.labelLarge, color = c.textMuted) })
                RowDivider()
                val available = (updateStatus as? UpdateChecker.Status.Available)?.release ?: latestRelease
                SettingRow(
                    title = if (available != null) "Update available: ${available.version}" else "Check for updates",
                    subtitle = when {
                        updateStatus is UpdateChecker.Status.Checking -> "Checking…"
                        available != null -> "Tap to open the download page and release notes"
                        updateStatus is UpdateChecker.Status.UpToDate -> "You're up to date"
                        updateStatus is UpdateChecker.Status.Failed -> "Couldn't check right now. Try again later"
                        else -> "Looks for a newer version on GitHub"
                    },
                    icon = EqIcons.Download,
                    onClick = {
                        if (available != null) openUrl(context, available.url) else vm.checkForUpdatesNow()
                    },
                    trailing = { EqIcon(EqIcons.Expand, null, size = 20.dp) }
                )
                RowDivider()
                SwitchRow(
                    title = "Automatic update checks",
                    subtitle = "Once a day, asks GitHub for the latest version",
                    checked = autoUpdateCheck,
                    onCheckedChange = { vm.setAutoUpdateCheck(it) },
                    icon = EqIcons.Info
                )
                RowDivider()
                SettingRow(title = "AutoEQ database", subtitle = "Served from GitHub Pages + on-device cache",
                    icon = EqIcons.Download)
                RowDivider()
                SettingRow(
                    title = "Open-source licences",
                    subtitle = "Libraries, font and data EQify is built with",
                    icon = EqIcons.Info,
                    onClick = { showLicencesSheet = true },
                    trailing = { EqIcon(EqIcons.Expand, null, size = 20.dp) }
                )
            }

            if (vm.isDebugBuild) {
                Group("Debug") {
                    SettingRow(
                        title = "Send test error",
                        subtitle = crashReportMessage ?: "Sends a handled test event to Sentry",
                        icon = EqIcons.Play,
                        onClick = { vm.sendTestCrashReport() }
                    )
                    RowDivider()
                    SettingRow(
                        title = "Crash app",
                        subtitle = "Crashes EQify on purpose; reopen it so the report uploads",
                        icon = EqIcons.Warning, destructive = true,
                        onClick = { vm.crashAppForTest() }
                    )
                }
            }
        }
    }
}

// ── Layout helpers ────────────────────────────────────────────────────

@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    // SectionHeader adds 4 dp of its own; 12 more lines the title up with row text at 16 dp.
    SectionHeader(title, modifier = Modifier.padding(start = EqSpacing.m, top = EqSpacing.l))
    Column(Modifier.fillMaxWidth(), content = content)
    HorizontalDivider(color = EqifyTheme.colors.outline)
}

@Composable
private fun RowDivider() = HorizontalDivider(color = EqifyTheme.colors.outline, modifier = Modifier.padding(start = 56.dp))

// ── Output protection ─────────────────────────────────────────────────

private fun OutputProtectionMode.label() = when (this) {
    OutputProtectionMode.BALANCED -> "Balanced"
    OutputProtectionMode.SAFE -> "Safe"
    OutputProtectionMode.OFF -> "Off"
}

/** Same status wording as before, describing what is actually active. */
private fun outputProtectionStatus(mode: OutputProtectionMode, limiter: LimiterDiagnosticStatus): String = when (mode) {
    OutputProtectionMode.BALANCED -> when (limiter.state) {
        LimiterDiagnosticState.ENABLED -> "Limiter active"
        LimiterDiagnosticState.ATTACHED -> "Ready when EQ is active"
        LimiterDiagnosticState.AVAILABLE -> "Preparing limiter"
        LimiterDiagnosticState.FAILED,
        LimiterDiagnosticState.UNSUPPORTED -> "Using Safe because the limiter isn't available"
    }
    OutputProtectionMode.SAFE -> when (limiter.state) {
        LimiterDiagnosticState.FAILED,
        LimiterDiagnosticState.UNSUPPORTED -> "Balanced unavailable on this device"
        else -> "Maximum compatible protection"
    }
    OutputProtectionMode.OFF -> "No output protection"
}

@Composable
private fun OutputProtectionSheet(
    selectedMode: OutputProtectionMode,
    limiterStatus: LimiterDiagnosticStatus,
    onSelect: (OutputProtectionMode) -> Unit,
    onDismiss: () -> Unit
) {
    val c = EqifyTheme.colors
    val balancedAvailable = limiterStatus.state != LimiterDiagnosticState.FAILED &&
        limiterStatus.state != LimiterDiagnosticState.UNSUPPORTED
    EqSheet(
        title = "Output protection",
        subtitle = "How EQify handles peaks created by boosted EQ bands.",
        onDismiss = onDismiss
    ) {
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(EqSpacing.s)) {
            ProtectionOption(
                mode = OutputProtectionMode.BALANCED, selectedMode = selectedMode, title = "Balanced",
                description = "Uses Android's real-time limiter to catch peaks while keeping the requested EQ curve.",
                caution = "Android 9+ only. Very loud peaks may be reduced slightly.",
                enabled = balancedAvailable, onSelect = onSelect
            )
            if (!balancedAvailable) {
                StatusBanner(
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P)
                        "Balanced requires Android 9 or newer. Safe is selected automatically."
                    else "The limiter could not start on this device. Safe is selected automatically.",
                    StatusKind.WARNING
                )
            }
            ProtectionOption(
                mode = OutputProtectionMode.SAFE, selectedMode = selectedMode, title = "Safe",
                description = "Reduces EQ band gains proportionally when the strongest boost exceeds +6 dB.",
                caution = "Large boosts sound less pronounced because the curve is reduced.",
                onSelect = onSelect
            )
            ProtectionOption(
                mode = OutputProtectionMode.OFF, selectedMode = selectedMode, title = "Off",
                description = "Applies the EQ curve without output protection.",
                caution = "High boosts can clip or distort. Lower the gains or listening volume if needed.",
                onSelect = onSelect
            )
        }
        Text(
            "Limiter state: ${limiterStatus.state.name.lowercase()}." +
                (limiterStatus.sessionId?.let { " Audio session $it." } ?: ""),
            style = MaterialTheme.typography.bodySmall.tabular(), color = c.textFaint
        )
    }
}

@Composable
private fun ProtectionOption(
    mode: OutputProtectionMode,
    selectedMode: OutputProtectionMode,
    title: String,
    description: String,
    caution: String,
    enabled: Boolean = true,
    onSelect: (OutputProtectionMode) -> Unit
) {
    val c = EqifyTheme.colors
    val selected = selectedMode == mode
    Surface(
        shape = EqifyShapes.medium,
        color = if (selected) c.accentContainer else c.surfaceHighest,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier
                .selectable(selected = selected, enabled = enabled, role = Role.RadioButton) { onSelect(mode) }
                .padding(EqSpacing.m),
            verticalAlignment = Alignment.Top
        ) {
            RadioButton(selected = selected, onClick = null, enabled = enabled)
            Spacer(Modifier.width(EqSpacing.m))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall,
                    color = when { !enabled -> c.textFaint; selected -> c.onAccentContainer; else -> c.textPrimary })
                Text(description, style = MaterialTheme.typography.bodySmall,
                    color = if (selected) c.onAccentContainer else c.textMuted)
                Text("Caution: $caution", style = MaterialTheme.typography.bodySmall,
                    color = if (selected) c.onAccentContainer else c.textMuted)
            }
        }
    }
}

// ── Last.fm key ───────────────────────────────────────────────────────

@Composable
private fun LastFmKeySheet(savedKey: String, onSave: (String) -> Unit, onClear: () -> Unit, onDismiss: () -> Unit) {
    var draft by remember(savedKey) { mutableStateOf(savedKey) }
    EqSheet(
        title = "Last.fm API key",
        subtitle = "Optional. Sent only to Last.fm over HTTPS.",
        onDismiss = onDismiss,
        actions = {
            if (savedKey.isNotEmpty()) TextAction("Clear", onClear, destructive = true)
            PrimaryButton("Save", { onSave(draft) }, enabled = draft.trim() != savedKey)
        }
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("API key") },
            placeholder = { Text("Paste your Last.fm API key") },
            visualTransformation = PasswordVisualTransformation()
        )
    }
}

// ── Privacy ───────────────────────────────────────────────────────────

@Composable
private fun PrivacySheet(onDismiss: () -> Unit) {
    EqSheet(title = "About your data", onDismiss = onDismiss) {
        PrivacyPoint(EqIcons.Music, "Song detection",
            "The song title and artist may be sent to iTunes, to Last.fm (only if you add a key) and to the configured EQify genre service.")
        PrivacyPoint(EqIcons.GraphicEq, "Stored on this device",
            "Title, artist, time and the detected or chosen genre are kept locally for recent tracks and the genre mix. Clear them any time in Data & privacy.")
        PrivacyPoint(EqIcons.Headphones, "Headphone corrections",
            "A correction is downloaded only after you select a headphone.")
        PrivacyPoint(EqIcons.FileOpen, "Imported files",
            "GraphicEQ files are converted and stored on this device. They are not uploaded.")
        PrivacyPoint(EqIcons.Download, "Update checks",
            "Once a day (if on), EQify asks GitHub for the latest version. GitHub sees your IP address, nothing else.")
        PrivacyPoint(EqIcons.Error, "Crash reports",
            "If crash reports are on, crash and error details are sent to Sentry without song titles, artists, headphone names or keys.")
        val context = LocalContext.current
        TextAction("Read the full privacy policy", onClick = { openUrl(context, ReleaseLinks.PRIVACY_POLICY) })
    }
}

/** Public pages for this build (the code repository itself is private). */
private object ReleaseLinks {
    const val PRIVACY_POLICY = "https://github.com/parthsharma365628/eqify-releases/blob/main/PRIVACY.md"
}

private fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

@Composable
private fun PrivacyPoint(@DrawableRes icon: Int, title: String, body: String) {
    val c = EqifyTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(EqSpacing.m)) {
        EqIcon(icon, null, tint = c.textMuted, size = 20.dp)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
        }
    }
}

// ── Open-source licences ──────────────────────────────────────────────

private data class Credit(val name: String, val licence: String, val url: String)

private val credits = listOf(
    Credit("AutoEq headphone measurements (Jaakko Pasanen)", "MIT License", "https://github.com/jaakkopasanen/AutoEq"),
    Credit("Archivo font (Omnibus-Type)", "SIL Open Font License 1.1", "https://github.com/Omnibus-Type/Archivo"),
    Credit("AndroidX and Jetpack Compose", "Apache License 2.0", "https://developer.android.com/jetpack/androidx"),
    Credit("Kotlin and kotlinx.coroutines", "Apache License 2.0", "https://github.com/Kotlin/kotlinx.coroutines"),
    Credit("Retrofit and OkHttp (Square)", "Apache License 2.0", "https://github.com/square/retrofit"),
    Credit("Gson (Google)", "Apache License 2.0", "https://github.com/google/gson"),
    Credit("ZXing core", "Apache License 2.0", "https://github.com/zxing/zxing"),
    Credit("Sentry Android SDK", "MIT License", "https://github.com/getsentry/sentry-java"),
    Credit("Google code scanner (Play services)", "Android Software Development Kit License", "https://developers.google.com/ml-kit/terms")
)

@Composable
private fun LicencesSheet(onDismiss: () -> Unit) {
    val c = EqifyTheme.colors
    val context = LocalContext.current
    var showFontLicence by remember { mutableStateOf(false) }
    val fontLicence = remember {
        runCatching { context.resources.openRawResource(R.raw.archivo_ofl).bufferedReader().use { it.readText() } }
            .getOrDefault("")
    }
    EqSheet(title = "Open-source licences", subtitle = "Tap an entry to open its source.", onDismiss = onDismiss) {
        credits.forEach { credit ->
            SettingRow(
                title = credit.name,
                subtitle = credit.licence,
                onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(credit.url))) }
                }
            )
        }
        if (fontLicence.isNotBlank()) {
            TextAction(if (showFontLicence) "Hide font licence" else "Show full font licence",
                onClick = { showFontLicence = !showFontLicence })
            if (showFontLicence) Text(fontLicence, style = MaterialTheme.typography.bodySmall, color = c.textMuted)
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────

/** Pre-filled so bug reports say which build and phone they came from. */
private fun feedbackTemplate(): String = buildString {
    appendLine("What happened, or what would you like?")
    appendLine()
    appendLine()
    appendLine("Music app and headphones used:")
    appendLine()
    appendLine("---")
    appendLine("EQify ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
    appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
    append("Phone: ${Build.MANUFACTURER} ${Build.MODEL}")
}

private fun isNotificationServiceEnabled(context: Context): Boolean {
    val packageNames = Settings.Secure.getString(
        context.contentResolver, "enabled_notification_listeners"
    ) ?: return false
    return packageNames.split(":").any { flat ->
        val cn = ComponentName.unflattenFromString(flat)
        cn != null && TextUtils.equals(context.packageName, cn.packageName)
    }
}

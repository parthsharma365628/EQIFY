package com.example.eqify

import com.example.eqify.*
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.eqify.NowPlayingState
import com.example.eqify.SettingsViewModel
import com.example.eqify.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit = {},
    vm: SettingsViewModel = viewModel()
) {
    val context       = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // ── Persisted settings (from DataStore via ViewModel) ─────────────
    val isEqEnabled        by vm.isEqEnabled.collectAsState(initial = true)
    val autoGenreDetection by vm.autoGenreDetection.collectAsState(initial = true)
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
    var lastFmApiKeyDraft by remember(savedLastFmApiKey) {
        mutableStateOf(savedLastFmApiKey)
    }

    // ── Runtime / permission state ────────────────────────────────────
    var isPermissionGranted by remember { mutableStateOf(isNotificationServiceEnabled(context)) }
    var showPermissionDialog by remember { mutableStateOf(false) }
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var showOutputProtectionDialog by remember { mutableStateOf(false) }

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

    // ── Permission dialog ─────────────────────────────────────────────
    if (showPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            title = { Text("Permission Required", color = TextPrimary) },
            text = {
                Text(
                    "To detect music automatically, EQify needs 'Notification Access'. " +
                            "Please find 'EQify' in the next screen and enable it.",
                    color = TextSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showPermissionDialog = false
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }) { Text("Go to Settings", color = AccentPurpleLight) }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = Surface2Dark
        )
    }

    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            title = { Text("Clear downloaded corrections?", color = TextPrimary) },
            text = {
                Text(
                    "Selected AutoEQ corrections will be downloaded again when needed.",
                    color = TextSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showClearCacheDialog = false
                    vm.clearDownloadedCorrections()
                }) { Text("Clear", color = AccentPurpleLight) }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = Surface2Dark
        )
    }

    if (showOutputProtectionDialog) {
        OutputProtectionDialog(
            selectedMode = outputProtectionMode,
            limiterStatus = limiterStatus,
            onSelect = { mode ->
                vm.setOutputProtectionMode(mode)
                showOutputProtectionDialog = false
            },
            onDismiss = { showOutputProtectionDialog = false }
        )
    }

    cacheMessage?.let { message ->
        AlertDialog(
            onDismissRequest = vm::dismissCacheMessage,
            title = { Text("AutoEQ cache", color = TextPrimary) },
            text = { Text(message, color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = vm::dismissCacheMessage) {
                    Text("OK", color = AccentPurpleLight)
                }
            },
            containerColor = Surface2Dark
        )
    }

    // ── UI ────────────────────────────────────────────────────────────
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "Settings",
                        fontSize   = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color      = TextPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Text("←", fontSize = 24.sp, color = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = BgDark
                )
            )
        },
        containerColor = BgDark
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {

            // ── EQ ────────────────────────────────────────────────────
            SettingsGroup(title = "EQ") {
                ControlledSettingsItem(
                    title    = "Auto EQ",
                    subtitle = if (isEqEnabled) "Applying genre profiles automatically"
                    else "EQ is paused — audio is unmodified",
                    checked  = isEqEnabled,
                    onCheckedChange = { vm.setEqEnabled(it) }
                )
            }

            // ── Audio ─────────────────────────────────────────────────
            SettingsGroup(title = "AUDIO") {
                OutputProtectionSettingsItem(
                    mode = outputProtectionMode,
                    limiterStatus = limiterStatus,
                    onClick = { showOutputProtectionDialog = true }
                )
                ControlledSettingsItem(
                    title    = "Force Mono",
                    subtitle = "Combine audio channels",
                    checked  = forceMono,
                    onCheckedChange = { vm.setForceMono(it) }
                )
            }

            // ── Integration ───────────────────────────────────────────
            SettingsGroup(title = "INTEGRATION") {
                ControlledSettingsItem(
                    title    = "Media Listener Service",
                    subtitle = if (isPermissionGranted) "Automatically detecting music"
                    else "Permission required to detect music",
                    checked  = mediaListenerEnabled && isPermissionGranted,
                    onCheckedChange = { newValue ->
                        if (newValue) {
                            if (isPermissionGranted) {
                                vm.setMediaListenerEnabled(true)
                            } else {
                                showPermissionDialog = true
                            }
                        } else {
                            vm.setMediaListenerEnabled(false)
                        }
                    }
                )
                ControlledSettingsItem(
                    title    = "Auto-Genre Detection",
                    subtitle = if (autoGenreDetection) "EQ profile updates with each track"
                    else "EQ profile stays fixed — switch manually in EQ tab",
                    checked  = autoGenreDetection,
                    onCheckedChange = { vm.setAutoGenreDetection(it) }
                )
                ControlledSettingsItem(
                    title    = "Headphone Auto-Detection",
                    subtitle = if (headphoneAutoDetect)
                        "Auto-selects headphone on Bluetooth connect or wired plug-in"
                    else "Headphone selection stays fixed",
                    checked  = headphoneAutoDetect,
                    onCheckedChange = { vm.setHeadphoneAutoDetect(it) }
                )
            }

            SettingsGroup(title = "GENRE SERVICE") {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Last.fm API key",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = lastFmApiKeyDraft,
                        onValueChange = { lastFmApiKeyDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("Paste your Last.fm API key") },
                        visualTransformation = PasswordVisualTransformation(),
                        supportingText = {
                            Text("Optional. Sent only to Last.fm over HTTPS.")
                        }
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        if (lastFmApiKeyDraft.isNotEmpty()) {
                            TextButton(onClick = {
                                lastFmApiKeyDraft = ""
                                vm.setLastFmApiKey("")
                            }) { Text("Clear", color = TextSecondary) }
                        }
                        TextButton(
                            enabled = lastFmApiKeyDraft.trim() != savedLastFmApiKey,
                            onClick = { vm.setLastFmApiKey(lastFmApiKeyDraft) }
                        ) { Text("Save", color = AccentPurpleLight) }
                    }
                }
            }

            // ── Feedback ──────────────────────────────────────────────
            SettingsGroup(title = "FEEDBACK") {
                SimpleSettingsItem(
                    title    = "Send feedback",
                    subtitle = "Opens your email app — bug reports and ideas welcome",
                    onClick  = {
                        val mail = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
                            putExtra(Intent.EXTRA_SUBJECT, "EQify Android feedback")
                        }
                        runCatching { context.startActivity(Intent.createChooser(mail, "Send feedback")) }
                    }
                )
            }

            // ── About ─────────────────────────────────────────────────
            SettingsGroup(title = "ABOUT") {
                SimpleSettingsItem(
                    title = "Clear downloaded corrections",
                    subtitle = "Remove locally cached AutoEQ curves",
                    onClick = { showClearCacheDialog = true }
                )
                SimpleSettingsItem(
                    title    = "Version",
                    subtitle = "1.0.0 (Beta)",
                    onClick  = {}
                )
                SimpleSettingsItem(
                    title    = "AutoEQ Database",
                    subtitle = "Served from EQify backend + on-device cache",
                    onClick  = {}
                )
                SimpleSettingsItem(
                    title = "Privacy",
                    subtitle = "Song title and artist may be sent to the genre service. " +
                        "Headphone correction is fetched only after a headphone is selected.",
                    onClick = {}
                )
            }
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────

private fun isNotificationServiceEnabled(context: Context): Boolean {
    val packageNames = Settings.Secure.getString(
        context.contentResolver, "enabled_notification_listeners"
    ) ?: return false
    return packageNames.split(":").any { flat ->
        val cn = ComponentName.unflattenFromString(flat)
        cn != null && TextUtils.equals(context.packageName, cn.packageName)
    }
}

@Composable
fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(
            text          = title,
            fontSize      = 11.sp,
            color         = AccentPurpleLight,
            fontWeight    = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        Spacer(Modifier.height(8.dp))
        Card(
            shape  = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Surface2Dark)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) { content() }
        }
    }
}

@Composable
fun ControlledSettingsItem(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(16.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title,    fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            Text(text = subtitle, fontSize = 11.sp, color = TextSecondary)
        }
        Switch(
            checked         = checked,
            onCheckedChange = onCheckedChange,
            colors          = SwitchDefaults.colors(
                checkedThumbColor = androidx.compose.ui.graphics.Color.White,
                checkedTrackColor = AccentPurple
            )
        )
    }
}

@Composable
private fun OutputProtectionSettingsItem(
    mode: OutputProtectionMode,
    limiterStatus: LimiterDiagnosticStatus,
    onClick: () -> Unit
) {
    val subtitle = when (mode) {
        OutputProtectionMode.BALANCED -> when (limiterStatus.state) {
            LimiterDiagnosticState.ENABLED -> "Balanced - limiter active"
            LimiterDiagnosticState.ATTACHED -> "Balanced - ready when EQ is active"
            LimiterDiagnosticState.AVAILABLE -> "Balanced - preparing limiter"
            LimiterDiagnosticState.FAILED,
            LimiterDiagnosticState.UNSUPPORTED -> "Safe fallback - Balanced unavailable"
        }
        OutputProtectionMode.SAFE -> when (limiterStatus.state) {
            LimiterDiagnosticState.FAILED,
            LimiterDiagnosticState.UNSUPPORTED -> "Safe - Balanced unavailable on this device"
            else -> "Safe - maximum compatible protection"
        }
        OutputProtectionMode.OFF -> "Off - no output protection"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Output protection",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
            Text(text = subtitle, fontSize = 11.sp, color = TextSecondary)
        }
        TextButton(onClick = onClick) {
            Text("Change", color = AccentPurpleLight)
        }
    }
}

@Composable
private fun OutputProtectionDialog(
    selectedMode: OutputProtectionMode,
    limiterStatus: LimiterDiagnosticStatus,
    onSelect: (OutputProtectionMode) -> Unit,
    onDismiss: () -> Unit
) {
    val balancedAvailable = limiterStatus.state != LimiterDiagnosticState.FAILED &&
        limiterStatus.state != LimiterDiagnosticState.UNSUPPORTED

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Output protection", color = TextPrimary) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Choose how EQify handles peaks created by boosted EQ bands.",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(12.dp))

                OutputProtectionOption(
                    mode = OutputProtectionMode.BALANCED,
                    selectedMode = selectedMode,
                    title = "Balanced",
                    description = "Uses Android's real-time limiter to catch peaks while keeping the requested EQ curve.",
                    caution = "Android 9+ only. Very loud peaks may be reduced slightly.",
                    enabled = balancedAvailable,
                    onSelect = onSelect
                )
                if (!balancedAvailable) {
                    Text(
                        text = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                            "Balanced requires Android 9 or newer. Safe is selected automatically."
                        } else {
                            "The limiter could not start on this device. Safe is selected automatically."
                        },
                        modifier = Modifier.padding(start = 48.dp, end = 8.dp, bottom = 8.dp),
                        color = AccentPurpleLight,
                        fontSize = 11.sp
                    )
                }

                OutputProtectionOption(
                    mode = OutputProtectionMode.SAFE,
                    selectedMode = selectedMode,
                    title = "Safe",
                    description = "Reduces EQ band gains proportionally when the strongest boost exceeds +6 dB.",
                    caution = "Large boosts sound less pronounced because the curve is reduced.",
                    onSelect = onSelect
                )

                OutputProtectionOption(
                    mode = OutputProtectionMode.OFF,
                    selectedMode = selectedMode,
                    title = "Off",
                    description = "Applies the EQ curve without output protection.",
                    caution = "High boosts can clip or distort. Lower the gains or listening volume if needed.",
                    onSelect = onSelect
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = AccentPurpleLight)
            }
        },
        containerColor = Surface2Dark
    )
}

@Composable
private fun OutputProtectionOption(
    mode: OutputProtectionMode,
    selectedMode: OutputProtectionMode,
    title: String,
    description: String,
    caution: String,
    enabled: Boolean = true,
    onSelect: (OutputProtectionMode) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onSelect(mode) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        RadioButton(
            selected = selectedMode == mode,
            onClick = { onSelect(mode) },
            enabled = enabled
        )
        Column(modifier = Modifier.padding(top = 2.dp, end = 4.dp)) {
            Text(
                text = title,
                color = if (enabled) TextPrimary else TextSecondary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = description,
                color = TextSecondary,
                fontSize = 12.sp
            )
            Text(
                text = "Caution: $caution",
                color = TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun SimpleSettingsItem(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title,    fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            Text(text = subtitle, fontSize = 11.sp, color = TextSecondary)
        }
    }
}

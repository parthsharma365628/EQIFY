package com.example.eqify

import com.example.eqify.*
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
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
    val limitOutputGain    by vm.limitOutputGain.collectAsState(initial = true)
    val forceMono          by vm.forceMono.collectAsState(initial = false)
    val mediaListenerEnabled  by vm.mediaListenerEnabled.collectAsState(initial = true)
    val headphoneAutoDetect   by vm.headphoneAutoDetect.collectAsState(initial = true)

    // ── Runtime / permission state ────────────────────────────────────
    var isPermissionGranted by remember { mutableStateOf(isNotificationServiceEnabled(context)) }
    var showPermissionDialog by remember { mutableStateOf(false) }

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
                ControlledSettingsItem(
                    title    = "Limit Output Gain",
                    subtitle = "Avoid clipping during heavy EQing",
                    checked  = limitOutputGain,
                    onCheckedChange = { vm.setLimitOutputGain(it) }
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
                    title    = "Version",
                    subtitle = "1.0.0 (Beta)",
                    onClick  = {}
                )
                SimpleSettingsItem(
                    title    = "AutoEQ Database",
                    subtitle = "Served from EQify backend + on-device cache",
                    onClick  = {}
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
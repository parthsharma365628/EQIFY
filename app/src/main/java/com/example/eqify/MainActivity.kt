package com.example.eqify

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

class MainActivity : ComponentActivity() {

    // ── Permission launchers ──────────────────────────────────────────

    private val runtimePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        android.util.Log.d("MainActivity", "Runtime permissions: $results")
    }

    // ── Lifecycle ─────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Audio session + Bluetooth receivers live on [EqifyApplication] so they survive
        // activity destruction when the user switches to a music app.

        // Start foreground EQ service
        val serviceIntent = Intent(this, EqProcessingService::class.java).apply {
            action = EqProcessingService.ACTION_START
        }
        startForegroundService(serviceIntent)

        setContent {
            EQifyTheme {
                val onboardingPrefs = remember {
                    getSharedPreferences("eqify_onboarding", MODE_PRIVATE)
                }
                var showWalkthrough by remember {
                    mutableStateOf(!onboardingPrefs.getBoolean("permissions_explained", false))
                }
                EqifyApp()
                if (showWalkthrough) {
                    PermissionWalkthrough(
                        onOpenNotificationAccess = {
                            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        },
                        onRequestRuntimePermissions = {
                            val permissions = buildList {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                                    ContextCompat.checkSelfPermission(
                                        this@MainActivity,
                                        Manifest.permission.BLUETOOTH_CONNECT
                                    ) != PackageManager.PERMISSION_GRANTED
                                ) add(Manifest.permission.BLUETOOTH_CONNECT)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                    ContextCompat.checkSelfPermission(
                                        this@MainActivity,
                                        Manifest.permission.POST_NOTIFICATIONS
                                    ) != PackageManager.PERMISSION_GRANTED
                                ) add(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            if (permissions.isNotEmpty()) {
                                runtimePermissionsLauncher.launch(permissions.toTypedArray())
                            }
                        },
                        onFinish = {
                            onboardingPrefs.edit().putBoolean("permissions_explained", true).apply()
                            showWalkthrough = false
                        }
                    )
                }
            }
        }
    }

    // Do not unregister global receivers or release [EqEngine] here — the foreground
    // [EqProcessingService] keeps EQ active while the user listens in other apps.
}

@Composable
private fun PermissionWalkthrough(
    onOpenNotificationAccess: () -> Unit,
    onRequestRuntimePermissions: () -> Unit,
    onFinish: () -> Unit
) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val title = when (step) {
        0 -> "Welcome to EQify"
        1 -> "Notification access"
        2 -> "Bluetooth and EQ service"
        else -> "Setup complete"
    }
    val description = when (step) {
        0 -> "EQify needs a few Android permissions to detect the current song and keep EQ active."
        1 -> "Notification access lets EQify read only the song title and artist shown by your music app."
        2 -> "Bluetooth access identifies connected headphones. Notification permission keeps the foreground EQ service visible."
        else -> "The EQ service is ready. You can change these permissions later in Android Settings."
    }

    AlertDialog(
        onDismissRequest = {},
        title = { Text(title) },
        text = {
            Column {
                Text(description)
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(4) { index ->
                        Surface(
                            modifier = Modifier.padding(horizontal = 3.dp).size(7.dp),
                            shape = MaterialTheme.shapes.small,
                            color = if (index == step)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline
                        ) {}
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when (step) {
                    0 -> step = 1
                    1 -> {
                        step = 2
                        onOpenNotificationAccess()
                    }
                    2 -> {
                        onRequestRuntimePermissions()
                        step = 3
                    }
                    else -> onFinish()
                }
            }) {
                Text(
                    when (step) {
                        0 -> "Start"
                        1 -> "Open settings"
                        2 -> "Grant permissions"
                        else -> "Done"
                    }
                )
            }
        },
        dismissButton = {
            if (step > 0 && step < 3) {
                TextButton(onClick = { step += 1 }) { Text("Skip") }
            }
        }
    )
}

// ── Navigation ────────────────────────────────────────────────────────

@Composable
fun EqifyApp() {
    val navController     = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute      = navBackStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            if (currentRoute == "home" || currentRoute == "eq") {
                NavigationBar(containerColor = SurfaceDark, tonalElevation = 0.dp) {
                    NavigationBarItem(
                        icon     = { AppGlyph("Home", if (currentRoute == "home") TextPrimary else TextSecondary) },
                        label    = { Text("Home") },
                        selected = currentRoute == "home",
                        onClick  = { navController.navigate("home") { launchSingleTop = true } }
                    )
                    NavigationBarItem(
                        icon     = { AppGlyph("Equalizer", if (currentRoute == "eq") TextPrimary else TextSecondary) },
                        label    = { Text("EQ") },
                        selected = currentRoute == "eq",
                        onClick  = { navController.navigate("eq") { launchSingleTop = true } }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController    = navController,
            startDestination = "home",
            modifier         = Modifier.padding(innerPadding)
        ) {
            composable("home") {
                HomeScreen(
                    onNavigateToHeadphones = { navController.navigate("headphones") },
                    onNavigateToSettings   = { navController.navigate("settings") }
                )
            }
            composable("eq") { EqScreen() }
            composable("headphones") {
                HeadphonesScreen(onNavigateBack = { navController.popBackStack() })
            }
            composable("settings") {
                SettingsScreen(onNavigateBack = { navController.popBackStack() })
            }
        }
    }
}

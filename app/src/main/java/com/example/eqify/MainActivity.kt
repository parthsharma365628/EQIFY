package com.example.eqify

import android.Manifest
import android.content.Context.RECEIVER_EXPORTED
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

class MainActivity : ComponentActivity() {

    // ── Permission launchers ──────────────────────────────────────────

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        android.util.Log.d("MainActivity", "POST_NOTIFICATIONS granted=$granted")
    }

    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        android.util.Log.d("MainActivity", "BLUETOOTH_CONNECT granted=$granted")
        if (granted) BluetoothHeadphoneDetector.preloadHeadphoneNames()
    }

    // ── Lifecycle ─────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Android 13+ notification permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Register Spotify / YouTube Music audio session receiver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                EqEngine.sessionReceiver,
                EqEngine.getSessionIntentFilter(),
                RECEIVER_EXPORTED
            )
        } else {
            registerReceiver(
                EqEngine.sessionReceiver,
                EqEngine.getSessionIntentFilter()
            )
        }

        // Register Bluetooth headphone auto-detection receiver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                BluetoothHeadphoneDetector.receiver,
                BluetoothHeadphoneDetector.getIntentFilter(),
                RECEIVER_EXPORTED
            )
        } else {
            registerReceiver(
                BluetoothHeadphoneDetector.receiver,
                BluetoothHeadphoneDetector.getIntentFilter()
            )
        }

        // Request BLUETOOTH_CONNECT permission (Android 12+) for reading device names
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                BluetoothHeadphoneDetector.preloadHeadphoneNames()
            } else {
                bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            }
        } else {
            // Android < 12 — BLUETOOTH permission is install-time, no runtime request
            BluetoothHeadphoneDetector.preloadHeadphoneNames()
        }

        // Start foreground EQ service
        val serviceIntent = Intent(this, EqProcessingService::class.java).apply {
            action = EqProcessingService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        setContent {
            EQifyTheme {
                EqifyApp()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(EqEngine.sessionReceiver)
        unregisterReceiver(BluetoothHeadphoneDetector.receiver)
        EqEngine.releaseEqualizer()
    }
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
                NavigationBar {
                    NavigationBarItem(
                        icon     = { Text("🏠") },
                        label    = { Text("Home") },
                        selected = currentRoute == "home",
                        onClick  = { navController.navigate("home") { launchSingleTop = true } }
                    )
                    NavigationBarItem(
                        icon     = { Text("🎛️") },
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
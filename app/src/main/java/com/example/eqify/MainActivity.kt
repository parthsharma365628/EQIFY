package com.example.eqify

import com.example.eqify.ui.components.EqIcon
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.example.eqify.ui.components.PrimaryButton
import com.example.eqify.ui.components.TextAction
import com.example.eqify.ui.theme.EqSpacing
import com.example.eqify.ui.theme.PillShape
import com.example.eqify.ui.components.EqIcons
import com.example.eqify.ui.theme.EqifyColors
import com.example.eqify.ui.theme.EqifyTheme
import com.example.eqify.ui.theme.EQifyTheme
import com.example.eqify.ui.theme.ThemeStore
import com.example.eqify.ui.theme.rememberEqifyColors
import android.graphics.drawable.ColorDrawable
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
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

        // Only on a fresh launch: a recreated Activity would replay the same link.
        if (savedInstanceState == null) PendingProfileImport.offer(intent)

        setContent {
            // Live theme: ThemeStore was loaded synchronously in EqifyApplication,
            // so the first frame already uses the user's choice.
            val themeSettings by ThemeStore.settings.collectAsState()
            val colors = rememberEqifyColors(themeSettings)
            SideEffect { applySystemBars(colors) }
            EQifyTheme(colors = colors) {
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

    /** Status/navigation bar icons and window background follow the theme (light icons on dark, etc.). */
    @Suppress("DEPRECATION") // bar colours are ignored (edge-to-edge) on Android 15+, icon appearance still applies
    private fun applySystemBars(colors: EqifyColors) {
        val background = colors.background.toArgb()
        window.setBackgroundDrawable(ColorDrawable(background))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            window.statusBarColor = background
            window.navigationBarColor = colors.surface.toArgb()
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !colors.isDark
            isAppearanceLightNavigationBars = !colors.isDark
        }
    }

    // singleTask: a link or share while EQify is running arrives here.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        PendingProfileImport.offer(intent)
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

    val icon = when (step) {
        0 -> EqIcons.Equalizer
        1 -> EqIcons.Music
        2 -> EqIcons.Bluetooth
        else -> EqIcons.CheckCircle
    }
    val primaryLabel = when (step) {
        0 -> "Start"
        1 -> "Open settings"
        2 -> "Grant permissions"
        else -> "Done"
    }
    val c = EqifyTheme.colors
    // Same steps, texts and actions as before; only the presentation changed (UI_REDESIGN.md phase 5).
    AlertDialog(
        onDismissRequest = {},
        icon = {
            Box(Modifier.size(56.dp).clip(PillShape).background(c.accentContainer), contentAlignment = Alignment.Center) {
                EqIcon(icon, contentDescription = null, tint = c.onAccentContainer, size = 28.dp)
            }
        },
        title = { Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(EqSpacing.l)) {
                Text(description, style = MaterialTheme.typography.bodyMedium, color = c.textMuted,
                    textAlign = TextAlign.Center)
                Row(
                    Modifier.semantics { contentDescription = "Step ${step + 1} of 4" },
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(4) { index ->
                        val width by animateDpAsState(if (index == step) 20.dp else 8.dp, label = "step-dot")
                        Box(Modifier.height(8.dp).width(width).clip(PillShape)
                            .background(if (index == step) c.textPrimary else c.outline))
                    }
                }
            }
        },
        confirmButton = {
            PrimaryButton(primaryLabel, onClick = {
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
            })
        },
        dismissButton = {
            if (step > 0 && step < 3) TextAction("Skip", { step += 1 })
        }
    )
}

// ── Navigation ────────────────────────────────────────────────────────

@Composable
fun EqifyApp() {
    val navController     = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute      = navBackStackEntry?.destination?.route
    // Keep one Home UI model across top-level navigation. Genre resolution now
    // belongs to the application-owned GenreCoordinator, not this Activity.
    val homeViewModel: HomeViewModel = viewModel()

    fun navigateToTopLevel(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    // An incoming profile link/share opens the EQ tab, whose view model shows the preview.
    val pendingImport by PendingProfileImport.text.collectAsState()
    LaunchedEffect(pendingImport) {
        if (pendingImport != null && currentRoute != "eq") navigateToTopLevel("eq")
    }

    Scaffold(
        bottomBar = {
            if (currentRoute == "home" || currentRoute == "eq") {
                val c = EqifyTheme.colors
                // Accent-container indicator; icons tinted by NavigationBarItem colors (no hand-drawn glyphs).
                val itemColors = NavigationBarItemDefaults.colors(
                    selectedIconColor = c.onAccentContainer,
                    selectedTextColor = c.textPrimary,
                    indicatorColor = c.accentContainer,
                    unselectedIconColor = c.textMuted,
                    unselectedTextColor = c.textMuted
                )
                NavigationBar(containerColor = c.surface, tonalElevation = 0.dp) {
                    NavigationBarItem(
                        icon     = { EqIcon(EqIcons.Home, contentDescription = null, tint = LocalContentColor.current) },
                        label    = { Text("Home", style = MaterialTheme.typography.labelMedium) },
                        selected = currentRoute == "home",
                        onClick  = { navigateToTopLevel("home") },
                        colors   = itemColors
                    )
                    NavigationBarItem(
                        icon     = { EqIcon(EqIcons.Equalizer, contentDescription = null, tint = LocalContentColor.current) },
                        label    = { Text("EQ", style = MaterialTheme.typography.labelMedium) },
                        selected = currentRoute == "eq",
                        onClick  = { navigateToTopLevel("eq") },
                        colors   = itemColors
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
                    onNavigateToSettings   = { navController.navigate("settings") },
                    viewModel = homeViewModel
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

package com.liteplane.eqify

import com.liteplane.eqify.ui.components.EqIcon
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.liteplane.eqify.ui.components.PrimaryButton
import com.liteplane.eqify.ui.components.TextAction
import com.liteplane.eqify.ui.theme.EqSpacing
import com.liteplane.eqify.ui.theme.PillShape
import com.liteplane.eqify.ui.components.EqIcons
import com.liteplane.eqify.ui.theme.EqifyColors
import com.liteplane.eqify.ui.theme.EqifyTheme
import com.liteplane.eqify.ui.theme.EQifyTheme
import com.liteplane.eqify.ui.theme.ThemeStore
import com.liteplane.eqify.ui.theme.rememberEqifyColors
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
                LaunchedEffect(Unit) {
                    if (!onboardingPrefs.getBoolean("permissions_explained", false)) Onboarding.show()
                }
                val showWalkthrough by Onboarding.visible.collectAsState()
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
                            Onboarding.hide()
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

    override fun onResume() {
        super.onResume()
        (application as EqifyApplication).checkForUpdatesIfDue()
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
    // Tour pages first (FEATURES_BATCH3_PLAN.md §F), then the same permission steps as before.
    var step by rememberSaveable { mutableIntStateOf(0) }
    val total = 6
    val title = when (step) {
        0 -> "Welcome to EQify"
        1 -> "Made for your headphones"
        2 -> "Presets, bass and more"
        3 -> "Notification access"
        4 -> "Bluetooth and EQ service"
        else -> "Setup complete"
    }
    val description = when (step) {
        0 -> "EQify changes the sound to match the song that's playing: more punch for hip-hop, more air for " +
            "classical. Turn Auto off in Settings to pick a preset yourself on the EQ tab."
        1 -> "EQify corrects 6,000+ headphone models and remembers your choice for each device. " +
            "On the phone speaker or in the car, correction switches off by itself."
        2 -> "Save your own presets, add bass, and set how strong genre EQ is on Home. " +
            "EQ → ⋮ → Show sound layers shows exactly what EQify changes."
        3 -> NotificationAccessInfo.dialogText(
            "Notification access lets EQify detect the song you play. You can skip it: " +
                "presets, headphone correction and bass still work."
        )
        4 -> "Bluetooth access identifies connected headphones. Notification permission keeps the foreground EQ service visible."
        else -> "The EQ service is ready. You can change these permissions later in Android Settings, " +
            "and see this tour again in Settings → About."
    }

    val icon = when (step) {
        0 -> EqIcons.Equalizer
        1 -> EqIcons.Headphones
        2 -> EqIcons.Tune
        3 -> EqIcons.Music
        4 -> EqIcons.Bluetooth
        else -> EqIcons.CheckCircle
    }
    val primaryLabel = when (step) {
        0, 1, 2 -> "Next"
        3 -> "Open settings"
        4 -> "Grant permissions"
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
                    repeat(total) { index ->
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
                    0, 1 -> step += 1
                    2 -> step = 3
                    3 -> {
                        step = 4
                        onOpenNotificationAccess()
                    }
                    4 -> {
                        onRequestRuntimePermissions()
                        step = 5
                    }
                    else -> onFinish()
                }
            })
        },
        dismissButton = {
            // Tour pages skip to the permissions; permission pages skip one step, as before.
            if (step < 3) TextAction("Skip tour", { step = 3 })
            else if (step < 5) TextAction("Skip", { step += 1 })
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

    // Window width decides bottom bar vs. rail (FEATURES_BATCH3_PLAN.md §H).
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    val wide = config.screenWidthDp >= 600
    val showNav = currentRoute == "home" || currentRoute == "eq"
    val c = EqifyTheme.colors

    Scaffold(
        bottomBar = {
            if (showNav && !wide) {
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
        Row(Modifier.fillMaxSize().padding(innerPadding)) {
            if (showNav && wide) {
                val railColors = NavigationRailItemDefaults.colors(
                    selectedIconColor = c.onAccentContainer,
                    selectedTextColor = c.textPrimary,
                    indicatorColor = c.accentContainer,
                    unselectedIconColor = c.textMuted,
                    unselectedTextColor = c.textMuted
                )
                NavigationRail(containerColor = c.surface) {
                    Spacer(Modifier.weight(1f))
                    NavigationRailItem(
                        icon     = { EqIcon(EqIcons.Home, contentDescription = null, tint = LocalContentColor.current) },
                        label    = { Text("Home", style = MaterialTheme.typography.labelMedium) },
                        selected = currentRoute == "home",
                        onClick  = { navigateToTopLevel("home") },
                        colors   = railColors
                    )
                    NavigationRailItem(
                        icon     = { EqIcon(EqIcons.Equalizer, contentDescription = null, tint = LocalContentColor.current) },
                        label    = { Text("EQ", style = MaterialTheme.typography.labelMedium) },
                        selected = currentRoute == "eq",
                        onClick  = { navigateToTopLevel("eq") },
                        colors   = railColors
                    )
                    Spacer(Modifier.weight(1f))
                }
            }
            // Content stays readable on tablets: capped and centred.
            Box(Modifier.weight(1f).fillMaxHeight().background(c.background), contentAlignment = Alignment.TopCenter) {
                NavHost(
                    navController    = navController,
                    startDestination = "home",
                    modifier         = Modifier.widthIn(max = 840.dp).fillMaxSize()
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
    }
}

package com.example.eqify

import com.example.eqify.screens.*
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp

import com.example.eqify.ui.theme.EQifyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            EQifyTheme {
                EQifyApp()
            }
        }
    }
}

@Composable
fun EQifyApp() {
    var selectedTab by remember { mutableStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                listOf(
                    "🏠" to "Home",
                    "🎚️" to "EQ",
                    "🎧" to "Headphones"
                ).forEachIndexed { index, (icon, label) ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Text(icon) },
                        label = { Text(label, fontSize = 9.sp) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            when (selectedTab) {
                0 -> HomeScreen(onNavigateToHeadphones = { selectedTab = 2 })
                1 -> EqScreen()
                2 -> HeadphonesScreen()
            }
        }
    }
}
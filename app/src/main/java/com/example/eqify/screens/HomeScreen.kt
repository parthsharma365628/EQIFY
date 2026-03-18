package com.example.eqify.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.eqify.ui.theme.*

@Composable
fun HomeScreen(onNavigateToHeadphones: () -> Unit = {}) {
    var autoEqEnabled by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .verticalScroll(rememberScrollState())
    ) {
        TopBar()
        NowPlayingCard(
            trackName = "INDUSTRY BABY",
            artistName = "Lil Nas X, Jack Harlow",
            genre = "Hip-Hop"
        )
        EqStatusCard(
            enabled = autoEqEnabled,
            onToggle = { autoEqEnabled = it }
        )
        HeadphoneCard(
            headphoneName = "Sony WH-1000XM5",
            onChangeClick = onNavigateToHeadphones
        )
        QuickPresets(activePreset = "Hip-Hop")
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
fun TopBar() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "EQify",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = AccentPurpleLight
        )
        IconButton(onClick = {}) {
            Text("⚙️", fontSize = 20.sp)
        }
    }
}

@Composable
fun NowPlayingCard(trackName: String, artistName: String, genre: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Surface2Dark)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "NOW PLAYING",
                fontSize = 9.sp,
                color = NeonCyan,
                letterSpacing = 2.sp
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = trackName,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Text(
                text = artistName,
                fontSize = 12.sp,
                color = TextSecondary
            )
            Spacer(Modifier.height(10.dp))
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = AccentPurple.copy(alpha = 0.15f)
            ) {
                Text(
                    text = "● $genre detected",
                    fontSize = 10.sp,
                    color = AccentPurpleLight,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
fun EqStatusCard(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Surface2Dark)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "AUTO EQ",
                    fontSize = 10.sp,
                    color = TextSecondary,
                    letterSpacing = 1.5.sp
                )
                Text(
                    text = if (enabled) "Active — Hip-Hop profile" else "Disabled",
                    fontSize = 12.sp,
                    color = if (enabled) AccentPurpleLight else TextSecondary,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = AccentPurple
                )
            )
        }
    }
}

@Composable
fun HeadphoneCard(headphoneName: String, onChangeClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Surface2Dark)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("🎧", fontSize = 28.sp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = headphoneName,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Text(
                    text = "AutoEQ profile loaded",
                    fontSize = 10.sp,
                    color = TextSecondary
                )
            }
            TextButton(onClick = onChangeClick) {
                Text(
                    text = "Change",
                    color = AccentPurpleLight,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
fun QuickPresets(activePreset: String) {
    val presets = listOf("Hip-Hop", "EDM", "Classical", "Rock", "Flat")
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = "QUICK PRESETS",
            fontSize = 9.sp,
            color = TextSecondary,
            letterSpacing = 1.5.sp
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            presets.forEach { preset ->
                val isActive = preset == activePreset
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (isActive) AccentPurple else Surface2Dark,
                    border = if (!isActive) BorderStroke(1.dp, AccentPurple.copy(alpha = 0.2f)) else null
                ) {
                    Text(
                        text = preset,
                        fontSize = 10.sp,
                        color = if (isActive) Color.White else TextSecondary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }
        }
    }
}
package com.example.eqify

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.eqify.ui.theme.*

data class Headphone(
    val brand: String,
    val model: String
)

val sampleHeadphones = listOf(
    Headphone("Sony",            "WH-1000XM5"),
    Headphone("Apple",           "AirPods Pro 2"),
    Headphone("Samsung",         "Galaxy Buds2 Pro"),
    Headphone("Bose",            "QuietComfort 45"),
    Headphone("Sennheiser",      "HD 650"),
    Headphone("Audio-Technica",  "ATH-M50x"),
    Headphone("Beyerdynamic",    "DT 770 Pro"),
    Headphone("JBL",             "Tune 760NC"),
    Headphone("Jabra",           "Evolve2 75")
)

@Composable
fun HeadphonesScreen() {
    var searchQuery by remember { mutableStateOf("") }
    var selectedModel by remember { mutableStateOf("WH-1000XM5") }

    val filtered = sampleHeadphones.filter {
        it.brand.contains(searchQuery, ignoreCase = true) ||
                it.model.contains(searchQuery, ignoreCase = true)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
    ) {
        Column(modifier = Modifier.padding(20.dp, 12.dp, 20.dp, 0.dp)) {
            Text(
                text = "Select Headphones",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = {
                    Text(
                        text = "Search headphone model...",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentPurple,
                    unfocusedBorderColor = AccentPurple.copy(alpha = 0.2f),
                    cursorColor = AccentPurple
                ),
                singleLine = true
            )
            Spacer(Modifier.height(12.dp))
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filtered) { hp ->
                HeadphoneItem(
                    headphone = hp,
                    isSelected = hp.model == selectedModel,
                    onClick = { selectedModel = hp.model }
                )
            }
        }
    }
}

@Composable
fun HeadphoneItem(headphone: Headphone, isSelected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            1.dp,
            if (isSelected) AccentPurple else AccentPurple.copy(alpha = 0.1f)
        ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) AccentPurple.copy(alpha = 0.1f) else Surface2Dark
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("🎧", fontSize = 24.sp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = headphone.brand,
                    fontSize = 9.sp,
                    color = TextSecondary,
                    letterSpacing = 0.5.sp
                )
                Text(
                    text = headphone.model,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
            }
            if (isSelected) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = AccentPurple
                ) {
                    Text(
                        text = "✓",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(6.dp)
                    )
                }
            }
        }
    }
}
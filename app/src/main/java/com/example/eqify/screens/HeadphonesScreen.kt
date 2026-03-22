package com.example.eqify

import android.app.Application
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
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.eqify.ui.theme.*
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class HeadphoneResult(val name: String, val type: String)

@OptIn(FlowPreview::class)
class HeadphonesViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as EqifyApplication).repository

    private val _results = MutableStateFlow<List<HeadphoneResult>>(emptyList())
    val results: StateFlow<List<HeadphoneResult>> = _results.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _localSelectedHeadphone = MutableStateFlow(NowPlayingState.selectedHeadphone.value)
    val localSelectedHeadphone: StateFlow<String> = _localSelectedHeadphone.asStateFlow()

    val searchQuery = MutableStateFlow("")

    private val _showCustomDialog = MutableStateFlow(false)
    val showCustomDialog: StateFlow<Boolean> = _showCustomDialog.asStateFlow()

    init {
        viewModelScope.launch {
            searchQuery
                .debounce(300)
                .distinctUntilChanged()
                .collect { query -> searchHeadphones(query) }
        }
        searchHeadphones("")
    }

    private fun searchHeadphones(query: String) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val response = RetrofitClient.apiService.getHeadphones(query)
                _results.value = response.map { HeadphoneResult(name = it.name, type = it.type) }
            } catch (e: Exception) {
                _results.value = emptyList()
                e.printStackTrace()
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun selectHeadphoneLocally(name: String) { _localSelectedHeadphone.value = name }

    fun confirmSelection() {
        val name = _localSelectedHeadphone.value
        NowPlayingState.updateSelectedHeadphone(name)
        viewModelScope.launch { repository.setSelectedHeadphone(name) }
    }

    fun showCustomDialog() { _showCustomDialog.value = true }
    fun hideCustomDialog() { _showCustomDialog.value = false }

    fun saveCustomHeadphone(name: String) {
        viewModelScope.launch {
            repository.saveCustomHeadphone(name, emptyMap())
            repository.setSelectedHeadphone(name)
            NowPlayingState.updateSelectedHeadphone(name)
            selectHeadphoneLocally(name)
            _showCustomDialog.value = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HeadphonesScreen(
    onNavigateBack: () -> Unit = {},
    vm: HeadphonesViewModel = viewModel()
) {
    val results by vm.results.collectAsState()
    val isLoading by vm.isLoading.collectAsState()
    val localSelectedHeadphone by vm.localSelectedHeadphone.collectAsState()
    val searchQuery by vm.searchQuery.collectAsState()
    val showCustomDialog by vm.showCustomDialog.collectAsState()

    if (showCustomDialog) {
        var customName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { vm.hideCustomDialog() },
            containerColor = Surface2Dark,
            title = {
                Text(
                    "Add custom headphone",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    Text(
                        "Enter a name for your headphone.",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = customName,
                        onValueChange = { customName = it },
                        placeholder = {
                            Text("e.g. My Headphones", color = TextSecondary)
                        },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentPurple,
                            unfocusedBorderColor = AccentPurple.copy(alpha = 0.3f),
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "After adding, go to the EQ screen and use Save to set custom values for each genre preset.",
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { if (customName.isNotBlank()) vm.saveCustomHeadphone(customName) },
                    enabled = customName.isNotBlank()
                ) {
                    Text("Add", color = AccentPurpleLight, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.hideCustomDialog() }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "Select Headphones",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Text("←", fontSize = 24.sp, color = TextPrimary)
                    }
                },
                actions = {
                    TextButton(onClick = {
                        vm.confirmSelection()
                        onNavigateBack()
                    }) {
                        Text("Done", color = AccentPurpleLight, fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = BgDark)
            )
        },
        containerColor = BgDark
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(
                    "8,850+ models from AutoEQ database",
                    fontSize = 11.sp,
                    color = TextSecondary
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { vm.searchQuery.value = it },
                    placeholder = {
                        Text("Search any headphone...", color = TextSecondary, fontSize = 12.sp)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentPurple,
                        unfocusedBorderColor = AccentPurple.copy(alpha = 0.2f),
                        cursorColor = AccentPurple,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    singleLine = true
                )
                Spacer(Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = AccentPurple.copy(alpha = 0.15f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("🎧", fontSize = 20.sp)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                "Currently selected",
                                fontSize = 9.sp,
                                color = AccentPurpleLight,
                                letterSpacing = 1.sp
                            )
                            Text(
                                localSelectedHeadphone,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                        }
                    }
                }
            }

            when {
                isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = AccentPurple)
                    }
                }
                results.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No headphones found", color = TextSecondary, fontSize = 13.sp)
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(results) { hp ->
                            HeadphoneListItem(
                                name = hp.name,
                                type = hp.type,
                                isSelected = hp.name == localSelectedHeadphone,
                                onClick = { vm.selectHeadphoneLocally(hp.name) }
                            )
                        }
                        item {
                            Spacer(Modifier.height(8.dp))
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { vm.showCustomDialog() },
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(
                                    1.dp, AccentPurple.copy(alpha = 0.3f)
                                ),
                                colors = CardDefaults.cardColors(containerColor = Surface2Dark)
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("➕", fontSize = 22.sp)
                                    Spacer(Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            "Add custom headphone",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = AccentPurpleLight
                                        )
                                        Text(
                                            "Set manual EQ for each genre",
                                            fontSize = 10.sp,
                                            color = TextSecondary
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HeadphoneListItem(name: String, type: String, isSelected: Boolean, onClick: () -> Unit) {
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
            Text("🎧", fontSize = 22.sp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    type.replaceFirstChar { it.uppercase() },
                    fontSize = 9.sp,
                    color = TextSecondary,
                    letterSpacing = 0.5.sp
                )
                Text(
                    name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
            }
            if (isSelected) {
                Surface(shape = RoundedCornerShape(50), color = AccentPurple) {
                    Text(
                        "✓",
                        fontSize = 11.sp,
                        color = androidx.compose.ui.graphics.Color.White,
                        modifier = Modifier.padding(6.dp)
                    )
                }
            }
        }
    }
}
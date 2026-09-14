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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class HeadphoneResult(val name: String, val type: String)

@OptIn(FlowPreview::class)
class HeadphonesViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as EqifyApplication).repository

    private val _results = MutableStateFlow<List<HeadphoneResult>>(emptyList())
    val results: StateFlow<List<HeadphoneResult>> = _results.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    val favorites: StateFlow<List<String>> = repository.favoriteHeadphones
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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
                .collectLatest { query -> searchHeadphones(query) }
        }
    }

    private suspend fun searchHeadphones(query: String) {
        _isLoading.value = true
        _errorMessage.value = null
        try {
            val response = RetrofitClient.apiService.getHeadphones(query)
            val saved = favorites.value
                .filter { query.isBlank() || it.contains(query, ignoreCase = true) }
                .map { HeadphoneResult(it, "favorite") }
            _results.value = (saved + response.map { HeadphoneResult(it.name, it.type) })
                .distinctBy { it.name.lowercase() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _results.value = favorites.value
                .filter { query.isBlank() || it.contains(query, ignoreCase = true) }
                .map { HeadphoneResult(it, "favorite") }
            _errorMessage.value = if (_results.value.isEmpty()) "Server unavailable" else "Offline favorites"
        } finally {
            _isLoading.value = false
        }
    }

    fun retry() {
        viewModelScope.launch { searchHeadphones(searchQuery.value) }
    }

    fun selectHeadphoneLocally(name: String) { _localSelectedHeadphone.value = name }

    fun toggleFavorite(name: String) {
        viewModelScope.launch { repository.toggleFavoriteHeadphone(name) }
    }

    fun confirmSelection() {
        val name = _localSelectedHeadphone.value
        NowPlayingState.updateSelectedHeadphone(name)
        viewModelScope.launch { repository.setSelectedHeadphone(name) }
    }

    fun showCustomDialog() { _showCustomDialog.value = true }
    fun hideCustomDialog() { _showCustomDialog.value = false }

    fun saveCustomHeadphone(name: String) {
        viewModelScope.launch {
            val trimmedName = name.trim()
            repository.setSelectedHeadphone(trimmedName)
            NowPlayingState.updateSelectedHeadphone(trimmedName)
            selectHeadphoneLocally(trimmedName)
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
    val errorMessage by vm.errorMessage.collectAsState()
    val localSelectedHeadphone by vm.localSelectedHeadphone.collectAsState()
    val searchQuery by vm.searchQuery.collectAsState()
    val showCustomDialog by vm.showCustomDialog.collectAsState()
    val correctionStatus by EqState.headphoneCorrectionStatus.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val correctionStatusText = when (val status = correctionStatus) {
        is HeadphoneCorrectionStatus.Loading ->
            if (status.headphoneName == localSelectedHeadphone) "Downloading correction..."
            else null
        is HeadphoneCorrectionStatus.Downloaded ->
            if (status.headphoneName == localSelectedHeadphone) "Correction downloaded and saved"
            else null
        is HeadphoneCorrectionStatus.Cached ->
            if (status.headphoneName == localSelectedHeadphone) "Using cached correction"
            else null
        is HeadphoneCorrectionStatus.Unavailable ->
            if (status.headphoneName == localSelectedHeadphone) "Correction unavailable - using flat EQ"
            else null
        HeadphoneCorrectionStatus.Idle -> null
    }

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
                        "Custom headphones use flat correction. Adjust the tone from the EQ screen.",
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
                if (errorMessage == "Offline favorites") {
                    Text("Server unavailable · showing saved favorites", fontSize = 10.sp, color = NeonCyan)
                }
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
                        AppGlyph("Headphones", AccentPurpleLight)
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
                            correctionStatusText?.let { statusText ->
                                Text(
                                    statusText,
                                    fontSize = 10.sp,
                                    color = if (statusText == "Using cached correction")
                                        NeonCyan else TextSecondary
                                )
                            }
                        }
                    }
                }
            }

            when {
                isLoading -> {
                    Column(
                        Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(color = AccentPurple)
                        Spacer(Modifier.height(12.dp))
                        Text("Searching...", color = TextSecondary, fontSize = 13.sp)
                    }
                }
                results.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            errorMessage ?: "No matches",
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                        if (errorMessage != null) {
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = { vm.retry() },
                                colors = ButtonDefaults.buttonColors(containerColor = AccentPurple)
                            ) {
                                Text("Retry")
                            }
                        }
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
                                isFavorite = favorites.any { it.equals(hp.name, ignoreCase = true) },
                                onFavoriteClick = { vm.toggleFavorite(hp.name) },
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
                                    Text("+", fontSize = 24.sp, color = AccentPurpleLight)
                                    Spacer(Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            "Add custom headphone",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = AccentPurpleLight
                                        )
                                        Text(
                                            "Use a manually named flat profile",
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
fun HeadphoneListItem(
    name: String,
    type: String,
    isSelected: Boolean,
    isFavorite: Boolean,
    onFavoriteClick: () -> Unit,
    onClick: () -> Unit
) {
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
            AppGlyph("Headphones")
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
            TextButton(
                onClick = onFavoriteClick,
                contentPadding = PaddingValues(4.dp),
                modifier = Modifier.size(36.dp)
            ) {
                Text(if (isFavorite) "★" else "☆", color = if (isFavorite) AccentPurpleLight else TextSecondary)
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

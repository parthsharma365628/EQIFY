package com.example.eqify

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

data class HeadphoneResult(val name: String, val type: String)

@OptIn(FlowPreview::class)
class HeadphonesViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as EqifyApplication).repository
    private val customProfilesRepository = (application as EqifyApplication).customEqProfiles
    val customProfiles = customProfilesRepository.profiles
    val correctionSelection = repository.headphoneCorrectionSelection
        .stateIn(viewModelScope, SharingStarted.Eagerly,
            HeadphoneCorrectionSelection.Dataset(NowPlayingState.selectedHeadphone.value))
    private val _importMessage = MutableStateFlow<String?>(customProfilesRepository.loadError.value)
    val importMessage = _importMessage.asStateFlow()

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
    private var pendingDatasetSelection = false

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

    private suspend fun searchHeadphones(query: String, forceRefresh: Boolean = false) {
        _isLoading.value = true
        _errorMessage.value = null
        try {
            val response = HeadphoneDataRepository.search(
                getApplication(),
                query,
                forceRefresh
            )
            val saved = favorites.value
                .filter { query.isBlank() || it.contains(query, ignoreCase = true) }
                .map { HeadphoneResult(it, "favorite") }
            _results.value = (saved + response.map { HeadphoneResult(it.name, it.type) })
                .distinctBy { it.name.lowercase() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _results.value = favorites.value
                .filter { query.isBlank() || it.contains(query, ignoreCase = true) }
                .map { HeadphoneResult(it, "favorite") }
            _errorMessage.value = if (_results.value.isEmpty()) {
                e.message ?: "Headphone database unavailable"
            } else {
                "Offline favorites"
            }
        } finally {
            _isLoading.value = false
        }
    }

    fun retry() {
        viewModelScope.launch { searchHeadphones(searchQuery.value, forceRefresh = true) }
    }

    fun selectHeadphoneLocally(name: String) {
        _localSelectedHeadphone.value = name
        pendingDatasetSelection = true
    }

    fun importGraphicEq(uri: Uri) {
        viewModelScope.launch {
            try {
                val app = getApplication<Application>()
                val profile = withContext(Dispatchers.IO) {
                    val filename = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME),
                        null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    } ?: "GraphicEQ.txt"
                    val bytes = ByteArrayOutputStream()
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        val chunk = ByteArray(8192)
                        while (true) {
                            val count = input.read(chunk)
                            if (count < 0) break
                            if (count > 0) bytes.write(chunk, 0, count)
                            require(bytes.size() <= 1_048_576) { "File exceeds 1 MiB limit." }
                        }
                    } ?: error("Could not open selected file.")
                    val text = Charsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
                    val result = GraphicEqImporter.convert(text)
                    customProfilesRepository.importProfile(filename.substringBeforeLast('.').ifBlank { "Imported EQ" },
                        filename, result.gains) to result.limited
                }
                _importMessage.value = "Imported ${profile.first.displayName}" +
                    if (profile.second) " (some gains limited to ±12 dB)." else ". Select it to apply."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _importMessage.value = "Import failed: ${e.message ?: "invalid file"}"
            }
        }
    }

    fun importPastedGraphicEq(name: String, table: String) {
        viewModelScope.launch {
            try {
                require(table.length <= 32_000) { "Pasted text is too long." }
                val result = GraphicEqImporter.convertPastedTable(table)
                val profile = withContext(Dispatchers.IO) {
                    customProfilesRepository.importProfile(name, "Pasted 10-band values", result.gains)
                }
                _importMessage.value = "Imported ${profile.displayName}" +
                    if (result.limited) " (some gains limited to ±12 dB). Select it to apply."
                    else ". Select it to apply."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _importMessage.value = "Import failed: ${e.message ?: "invalid table"}" }
        }
    }

    fun selectCustomProfile(id: String) {
        pendingDatasetSelection = false
        viewModelScope.launch {
            if (customProfilesRepository.find(id) == null) {
                _importMessage.value = "Profile no longer exists."
            } else {
                repository.selectCustomCorrection(id)
            }
        }
    }

    fun renameCustomProfile(id: String, name: String) {
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { customProfilesRepository.rename(id, name) } }
            catch (e: Exception) { _importMessage.value = "Rename failed: ${e.message}" }
        }
    }

    fun deleteCustomProfile(id: String) {
        viewModelScope.launch {
            try {
                if (correctionSelection.value == HeadphoneCorrectionSelection.Custom(id))
                    repository.selectNoCorrection()
                withContext(Dispatchers.IO) { customProfilesRepository.delete(id) }
                _importMessage.value = "Profile deleted. Flat correction is active if it was selected."
            } catch (e: Exception) { _importMessage.value = "Delete failed: ${e.message}" }
        }
    }

    fun toggleFavorite(name: String) {
        viewModelScope.launch { repository.toggleFavoriteHeadphone(name) }
    }

    fun confirmSelection() {
        if (!pendingDatasetSelection) return
        val name = _localSelectedHeadphone.value
        NowPlayingState.updateSelectedHeadphone(name)
        viewModelScope.launch { repository.setSelectedHeadphone(name) }
        pendingDatasetSelection = false
    }

    fun showCustomDialog() { _showCustomDialog.value = true }
    fun hideCustomDialog() { _showCustomDialog.value = false }

    fun saveCustomHeadphone(name: String) {
        viewModelScope.launch {
            val trimmedName = name.trim()
            repository.setSelectedHeadphone(trimmedName)
            NowPlayingState.updateSelectedHeadphone(trimmedName)
            _localSelectedHeadphone.value = trimmedName
            pendingDatasetSelection = false
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
    val customProfiles by vm.customProfiles.collectAsState()
    val selection by vm.correctionSelection.collectAsState()
    val importMessage by vm.importMessage.collectAsState()
    var renameProfile by remember { mutableStateOf<CustomEqProfile?>(null) }
    var deleteProfile by remember { mutableStateOf<CustomEqProfile?>(null) }
    var showPasteDialog by remember { mutableStateOf(false) }
    var pastedName by remember { mutableStateOf("") }
    var pastedTable by remember { mutableStateOf("") }
    var renameDraft by remember(renameProfile) { mutableStateOf(renameProfile?.displayName ?: "") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importGraphicEq(uri)
    }
    val correctionStatusText = when (val status = correctionStatus) {
        is HeadphoneCorrectionStatus.Loading ->
            if (status.headphoneName == localSelectedHeadphone) "Downloading correction..."
            else null
        is HeadphoneCorrectionStatus.Downloaded ->
            if (status.headphoneName == localSelectedHeadphone) "Gain values downloaded successfully"
            else null
        is HeadphoneCorrectionStatus.Cached ->
            if (status.headphoneName == localSelectedHeadphone) "Using cached correction"
            else null
        is HeadphoneCorrectionStatus.Failed ->
            if (status.headphoneName == localSelectedHeadphone) "Error: ${status.message}"
            else null
        is HeadphoneCorrectionStatus.Imported -> "Using imported profile: ${status.displayName}"
        is HeadphoneCorrectionStatus.ImportFailed -> "Error: ${status.message}"
        HeadphoneCorrectionStatus.Idle -> null
    }

    if (showPasteDialog) {
        AlertDialog(
            onDismissRequest = { showPasteDialog = false },
            title = { Text("Paste 10-band Graphic EQ") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Paste the Frequency (Hz) / Gain (dB) table: one pair per line. It will be converted to EQify's eight bands.",
                        color = TextSecondary, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = pastedName, onValueChange = { pastedName = it },
                        label = { Text("Profile name") }, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = pastedTable, onValueChange = { pastedTable = it },
                        label = { Text("10-band values") },
                        placeholder = { Text("31  12.0\n63  1.1\n125  0.6\n…\n16000  -12.0") },
                        minLines = 6, maxLines = 10)
                }
            },
            confirmButton = { TextButton(
                enabled = pastedName.isNotBlank() && pastedTable.isNotBlank(),
                onClick = {
                    vm.importPastedGraphicEq(pastedName, pastedTable)
                    showPasteDialog = false
                }) { Text("Import") } },
            dismissButton = { TextButton(onClick = { showPasteDialog = false }) { Text("Cancel") } }
        )
    }

    renameProfile?.let { profile ->
        AlertDialog(onDismissRequest = { renameProfile = null },
            title = { Text("Rename imported profile") },
            text = { OutlinedTextField(renameDraft, onValueChange = { renameDraft = it }, singleLine = true) },
            confirmButton = { TextButton(enabled = renameDraft.isNotBlank(), onClick = {
                vm.renameCustomProfile(profile.id, renameDraft); renameProfile = null
            }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renameProfile = null }) { Text("Cancel") } })
    }
    deleteProfile?.let { profile ->
        AlertDialog(onDismissRequest = { deleteProfile = null },
            title = { Text("Delete imported profile?") },
            text = { Text(profile.displayName) },
            confirmButton = { TextButton(onClick = {
                vm.deleteCustomProfile(profile.id); deleteProfile = null
            }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteProfile = null }) { Text("Cancel") } })
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
                    "6,028 models from AutoEQ database",
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
                TextButton(onClick = { picker.launch(arrayOf("text/plain")) }) {
                    Text("Import GraphicEQ file", color = AccentPurpleLight)
                }
                TextButton(onClick = { showPasteDialog = true }) {
                    Text("Paste 10-band values", color = AccentPurpleLight)
                }
                importMessage?.let { Text(it, color = TextSecondary, fontSize = 11.sp) }
                if (customProfiles.isNotEmpty()) {
                    Text("MY CUSTOM PROFILES", color = AccentPurpleLight, fontSize = 11.sp)
                    Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                        customProfiles.sortedByDescending { it.importedAtEpochMillis }.forEach { profile ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { vm.selectCustomProfile(profile.id) }, modifier = Modifier.weight(1f)) {
                                    Text((if (selection == HeadphoneCorrectionSelection.Custom(profile.id)) "✓ " else "") +
                                        profile.displayName + " · GraphicEQ", color = TextPrimary)
                                }
                                TextButton(onClick = { renameProfile = profile }) { Text("Rename") }
                                TextButton(onClick = { deleteProfile = profile }) { Text("Delete") }
                            }
                        }
                    }
                }
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
                                    color = when (correctionStatus) {
                                        is HeadphoneCorrectionStatus.Failed ->
                                            MaterialTheme.colorScheme.error
                                        is HeadphoneCorrectionStatus.Downloaded,
                                        is HeadphoneCorrectionStatus.Cached,
                                        is HeadphoneCorrectionStatus.Imported -> NeonCyan
                                        is HeadphoneCorrectionStatus.ImportFailed -> MaterialTheme.colorScheme.error
                                        else -> TextSecondary
                                    }
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

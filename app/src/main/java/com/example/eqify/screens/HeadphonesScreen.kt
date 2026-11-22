package com.example.eqify

import android.app.Application
import android.net.Uri
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
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
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.example.eqify.ui.components.*

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
                    val document = BoundedTextReader.read(app, uri, maxBytes = 1_048_576, limitLabel = "1 MiB")
                    val filename = document.displayName ?: "GraphicEQ.txt"
                    val result = GraphicEqImporter.convert(document.text)
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
                _importMessage.value = "This correction no longer exists."
            } else {
                repository.selectCustomCorrection(id)
                HeadphoneDetector.rememberForCurrentOutput(DeviceChoice.Custom(id))
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
                _importMessage.value = "Correction deleted. If it was in use, no headphone correction is applied now."
            } catch (e: Exception) { _importMessage.value = "Delete failed: ${e.message}" }
        }
    }

    // ── Sharing an imported correction (flat tone, correction only) ────

    private val _shareDraft = MutableStateFlow<ShareDraft?>(null)
    val shareDraft: StateFlow<ShareDraft?> = _shareDraft.asStateFlow()

    fun openShareCustomProfile(id: String) {
        val profile = customProfilesRepository.find(id)
        if (profile == null) {
            _importMessage.value = "This correction no longer exists."
            return
        }
        if (profile.gains.size != 8 || profile.gains.any { !it.isFinite() || it !in -12f..12f }) {
            _importMessage.value = "This profile can't be shared: its saved values are invalid."
            return
        }
        _shareDraft.value = ShareDraft(
            name = profile.displayName,
            tone = List(8) { 0f },
            correction = SharedCorrection(SharedCorrection.Kind.CUSTOM, profile.displayName, profile.gains),
            bass = null,
            correctionOnly = true
        )
    }

    fun closeShare() { _shareDraft.value = null }

    fun onCodeCopied() {
        _importMessage.value = "Profile code copied. Paste it anywhere to share."
        _shareDraft.value = null
    }

    fun exportShareToFile(uri: Uri) {
        val draft = _shareDraft.value ?: return
        viewModelScope.launch {
            _importMessage.value = try {
                withContext(Dispatchers.IO) {
                    ProfileShareIo.writeJsonFile(getApplication(), uri, draft.profile)
                }
                "Saved \"${draft.name}\" to a file."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Could not save the file: ${e.message ?: "unknown error"}"
            }
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
        HeadphoneDetector.rememberForCurrentOutput(DeviceChoice.Dataset(name))
        pendingDatasetSelection = false
    }

    fun showCustomDialog() { _showCustomDialog.value = true }
    fun hideCustomDialog() { _showCustomDialog.value = false }

    fun saveCustomHeadphone(name: String) {
        viewModelScope.launch {
            val trimmedName = name.trim()
            repository.setSelectedHeadphone(trimmedName)
            NowPlayingState.updateSelectedHeadphone(trimmedName)
            HeadphoneDetector.rememberForCurrentOutput(DeviceChoice.Dataset(trimmedName))
            _localSelectedHeadphone.value = trimmedName
            pendingDatasetSelection = false
            _showCustomDialog.value = false
        }
    }
}

// ── HeadphonesScreen ──────────────────────────────────────────────────
//
// UI only (UI_REDESIGN.md §6 "Headphones"). All state and actions come from
// HeadphonesViewModel / EqState exactly as before. Done confirms a pending
// dataset selection; Back leaves without confirming (unchanged).

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HeadphonesScreen(
    onNavigateBack: () -> Unit = {},
    vm: HeadphonesViewModel = viewModel()
) {
    val c = EqifyTheme.colors
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
    var showPasteSheet by remember { mutableStateOf(false) }
    var renameDraft by remember(renameProfile) { mutableStateOf(renameProfile?.displayName ?: "") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importGraphicEq(uri)
    }
    val shareDraft by vm.shareDraft.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val saveShareFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) vm.exportShareToFile(uri) }

    shareDraft?.let { draft ->
        ShareProfileDialog(
            draft = draft,
            onIncludeCorrection = {},
            onIncludeBass = {},
            onCopy = {
                clipboard.setText(AnnotatedString(draft.code))
                vm.onCodeCopied()
            },
            onShareText = {
                context.startActivity(ProfileShareIo.shareTextChooser(draft.name, draft.code))
                vm.closeShare()
            },
            onSaveFile = { saveShareFile.launch(draft.suggestedFileName) },
            onDismiss = vm::closeShare
        )
    }

    if (showPasteSheet) {
        PasteGraphicEqSheet(
            onImport = { name, table -> vm.importPastedGraphicEq(name, table); showPasteSheet = false },
            onDismiss = { showPasteSheet = false }
        )
    }

    renameProfile?.let { profile ->
        AlertDialog(
            onDismissRequest = { renameProfile = null },
            title = { Text("Rename imported correction", style = MaterialTheme.typography.titleMedium) },
            text = {
                OutlinedTextField(renameDraft, onValueChange = { renameDraft = it }, singleLine = true,
                    label = { Text("Correction name") })
            },
            confirmButton = {
                TextAction("Save", enabled = renameDraft.isNotBlank(), onClick = {
                    vm.renameCustomProfile(profile.id, renameDraft); renameProfile = null
                })
            },
            dismissButton = { TextAction("Cancel", { renameProfile = null }) }
        )
    }
    deleteProfile?.let { profile ->
        ConfirmDialog(
            title = "Delete imported correction?",
            message = "\"${profile.displayName}\" will be removed from this device. If it is in use, flat correction is used instead.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = { vm.deleteCustomProfile(profile.id); deleteProfile = null },
            onDismiss = { deleteProfile = null }
        )
    }

    if (showCustomDialog) {
        var customName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { vm.hideCustomDialog() },
            title = { Text("Name a headphone", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(EqSpacing.m)) {
                    OutlinedTextField(
                        value = customName,
                        onValueChange = { customName = it },
                        label = { Text("Headphone name") },
                        placeholder = { Text("e.g. My Headphones") },
                        singleLine = true
                    )
                    Text("Named headphones use flat correction. Adjust the tone from the EQ screen.",
                        style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                }
            },
            confirmButton = {
                TextAction("Add", { if (customName.isNotBlank()) vm.saveCustomHeadphone(customName) },
                    enabled = customName.isNotBlank())
            },
            dismissButton = { TextAction("Cancel", { vm.hideCustomDialog() }) }
        )
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Headphones", style = MaterialTheme.typography.titleLarge, color = c.textPrimary) },
                navigationIcon = { IconAction(EqIcons.Back, "Back", onNavigateBack, tint = c.textPrimary) },
                actions = {
                    TextAction("Done", {
                        vm.confirmSelection()
                        onNavigateBack()
                    })
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = c.background)
            )
        },
        containerColor = c.background
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Search stays pinned while the list below scrolls.
            Column(Modifier.padding(horizontal = EqSpacing.screen).padding(bottom = EqSpacing.s)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { vm.searchQuery.value = it },
                    placeholder = { Text("Search any headphone") },
                    leadingIcon = { EqIcon(EqIcons.Search, null) },
                    trailingIcon = if (searchQuery.isNotEmpty()) {
                        { IconAction(EqIcons.Close, "Clear search", { vm.searchQuery.value = "" }) }
                    } else null,
                    singleLine = true,
                    shape = EqifyShapes.medium,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("6,028 models from the AutoEQ database", style = MaterialTheme.typography.bodySmall,
                    color = c.textMuted, modifier = Modifier.padding(start = EqSpacing.xs, top = EqSpacing.xs))
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = EqSpacing.screen, end = EqSpacing.screen, bottom = EqSpacing.xl),
                verticalArrangement = Arrangement.spacedBy(EqSpacing.s)
            ) {
                item(key = "selected") {
                    SelectedHeadphoneCard(localSelectedHeadphone, correctionStatus)
                }
                item(key = "add") {
                    AddCorrectionButton(
                        onImportFile = { picker.launch(arrayOf("text/plain")) },
                        onPaste = { showPasteSheet = true }
                    )
                }
                importMessage?.let { message ->
                    item(key = "importMessage") { StatusBanner(message, StatusKind.NEUTRAL, Modifier.animateItem()) }
                }
                if (errorMessage == "Offline favorites") {
                    item(key = "offline") {
                        StatusBanner("Can't reach the headphone database. Showing your saved favorites.", StatusKind.WARNING,
                            Modifier.animateItem(), actionLabel = "Retry", onAction = { vm.retry() })
                    }
                }

                if (customProfiles.isNotEmpty()) {
                    item(key = "customHeader") { SectionHeader("Imported corrections", modifier = Modifier.padding(top = EqSpacing.s)) }
                    item(key = "customList") {
                        EqCard(contentPadding = PaddingValues(0.dp)) {
                            customProfiles.sortedByDescending { it.importedAtEpochMillis }.forEachIndexed { i, profile ->
                                if (i > 0) HorizontalDivider(color = c.outline)
                                CustomProfileRow(
                                    profile = profile,
                                    selected = selection == HeadphoneCorrectionSelection.Custom(profile.id),
                                    onSelect = { vm.selectCustomProfile(profile.id) },
                                    onShare = { vm.openShareCustomProfile(profile.id) },
                                    onRename = { renameProfile = profile },
                                    onDelete = { deleteProfile = profile }
                                )
                            }
                        }
                    }
                }

                item(key = "catalogHeader") {
                    SectionHeader(if (searchQuery.isBlank()) "Favorites and catalog" else "Results",
                        modifier = Modifier.padding(top = EqSpacing.s))
                }
                when {
                    isLoading -> item(key = "loading") {
                        Row(Modifier.fillMaxWidth().padding(EqSpacing.xl), horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(color = c.accent, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(EqSpacing.m))
                            Text("Searching…", style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
                        }
                    }
                    results.isEmpty() -> item(key = "empty") {
                        EmptyState(
                            icon = if (errorMessage != null) EqIcons.Warning else EqIcons.Search,
                            title = errorMessage ?: "No matches",
                            body = if (errorMessage == null) "Try a shorter name, or add a correction above." else null,
                            actionLabel = if (errorMessage != null) "Retry" else null,
                            onAction = if (errorMessage != null) ({ vm.retry() }) else null
                        )
                    }
                    else -> items(results, key = { "hp:" + it.name.lowercase() }) { hp ->
                        HeadphoneListItem(
                            modifier = Modifier.animateItem(),
                            name = hp.name,
                            type = hp.type,
                            isSelected = hp.name == localSelectedHeadphone,
                            isFavorite = favorites.any { it.equals(hp.name, ignoreCase = true) },
                            onFavoriteClick = { vm.toggleFavorite(hp.name) },
                            onClick = { vm.selectHeadphoneLocally(hp.name) }
                        )
                    }
                }
                item(key = "nameHeadphone") {
                    SettingRow(
                        title = "Can't find your headphones?",
                        subtitle = "Add them by name. They play without a headphone correction.",
                        icon = EqIcons.Add,
                        onClick = { vm.showCustomDialog() },
                        modifier = Modifier.padding(top = EqSpacing.s)
                    )
                }
            }
        }
    }
}

// ── Pieces ────────────────────────────────────────────────────────────

private data class CorrectionUi(val label: String, val kind: StatusKind, val detail: String)

/** Same visibility rules as before: dataset statuses only for the shown name. */
private fun correctionUi(status: HeadphoneCorrectionStatus, name: String): CorrectionUi? = when (status) {
    is HeadphoneCorrectionStatus.Loading ->
        if (status.headphoneName == name) CorrectionUi("Downloading", StatusKind.NEUTRAL, "Downloading correction…") else null
    is HeadphoneCorrectionStatus.Downloaded ->
        if (status.headphoneName == name) CorrectionUi("Downloaded", StatusKind.SUCCESS, "Gain values downloaded") else null
    is HeadphoneCorrectionStatus.Cached ->
        if (status.headphoneName == name) CorrectionUi("Saved", StatusKind.SUCCESS, "Using saved correction") else null
    is HeadphoneCorrectionStatus.Failed ->
        if (status.headphoneName == name) CorrectionUi("Failed", StatusKind.ERROR, status.message) else null
    is HeadphoneCorrectionStatus.Imported -> CorrectionUi("Imported", StatusKind.ACTIVE, "Using imported correction: ${status.displayName}")
    is HeadphoneCorrectionStatus.ImportFailed -> CorrectionUi("Failed", StatusKind.ERROR, status.message)
    is HeadphoneCorrectionStatus.NotOnHeadphones ->
        CorrectionUi("Off", StatusKind.NEUTRAL, "${status.outputName}: no headphone correction right now. It returns when these headphones reconnect.")
    HeadphoneCorrectionStatus.Idle -> null
}

@Composable
private fun SelectedHeadphoneCard(name: String, status: HeadphoneCorrectionStatus) {
    val c = EqifyTheme.colors
    val ui = correctionUi(status, name)
    EqCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(EqSpacing.m)) {
            Box(Modifier.size(44.dp).clip(PillShape).background(c.accentContainer), contentAlignment = Alignment.Center) {
                EqIcon(EqIcons.Headphones, null, tint = c.onAccentContainer)
            }
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
            }
            ui?.let { StatusChip(it.label, it.kind) }
        }
        ui?.let {
            Spacer(Modifier.height(EqSpacing.s))
            Text(it.detail, style = MaterialTheme.typography.bodySmall,
                color = if (it.kind == StatusKind.ERROR) c.error else c.textMuted)
        }
    }
}

@Composable
private fun AddCorrectionButton(onImportFile: () -> Unit, onPaste: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TonalButton("Add correction", { open = true }, icon = EqIcons.Add)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = EqifyTheme.colors.surfaceHighest) {
            HpMenuItem("Import GraphicEQ file", EqIcons.FileOpen) { open = false; onImportFile() }
            HpMenuItem("Paste 10-band values", EqIcons.Paste) { open = false; onPaste() }
        }
    }
}

@Composable
private fun HpMenuItem(text: String, @DrawableRes icon: Int, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, style = MaterialTheme.typography.bodyMedium, color = EqifyTheme.colors.textPrimary) },
        leadingIcon = { EqIcon(icon, null, size = 20.dp) },
        onClick = onClick
    )
}

@Composable
private fun CustomProfileRow(
    profile: CustomEqProfile, selected: Boolean,
    onSelect: () -> Unit, onShare: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit
) {
    val c = EqifyTheme.colors
    var menu by remember { mutableStateOf(false) }
    SettingRow(
        title = profile.displayName,
        subtitle = if (selected) "In use" else "From a GraphicEQ file",
        icon = if (selected) EqIcons.CheckCircle else EqIcons.GraphicEq,
        onClick = onSelect,
        modifier = Modifier.semantics { this.selected = selected },
        trailing = {
            Box {
                IconAction(EqIcons.More, "Options for ${profile.displayName}", { menu = true })
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = c.surfaceHighest) {
                    HpMenuItem("Share", EqIcons.Share) { menu = false; onShare() }
                    HpMenuItem("Rename", EqIcons.Edit) { menu = false; onRename() }
                    HpMenuItem("Delete", EqIcons.Delete) { menu = false; onDelete() }
                }
            }
        }
    )
}

@Composable
fun HeadphoneListItem(
    name: String,
    type: String,
    isSelected: Boolean,
    isFavorite: Boolean,
    onFavoriteClick: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = EqifyTheme.colors
    val bg by animateColorAsState(if (isSelected) c.accentContainer else c.surfaceRaised, label = "hp-row-bg")
    Surface(
        shape = EqifyShapes.medium,
        color = bg,
        modifier = modifier.fillMaxWidth()
    ) {
        SettingRow(
            title = name,
            subtitle = type.replaceFirstChar { it.uppercase() },
            icon = if (isSelected) EqIcons.CheckCircle else EqIcons.Headphones,
            onClick = onClick,
            modifier = Modifier.semantics { selected = isSelected },
            trailing = {
                IconAction(
                    icon = if (isFavorite) EqIcons.Star else EqIcons.StarOutline,
                    contentDescription = if (isFavorite) "Remove $name from favorites" else "Add $name to favorites",
                    onClick = onFavoriteClick,
                    tint = if (isFavorite) c.textPrimary else c.textMuted
                )
            }
        )
    }
}

@Composable
private fun PasteGraphicEqSheet(onImport: (String, String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var table by remember { mutableStateOf("") }
    EqSheet(
        title = "Paste 10-band values",
        subtitle = "One Frequency (Hz) / Gain (dB) pair per line, as shown on autoeq.app.",
        onDismiss = onDismiss,
        actions = {
            PrimaryButton("Import", { onImport(name, table) }, enabled = name.isNotBlank() && table.isNotBlank())
        }
    ) {
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Profile name") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = table, onValueChange = { table = it }, label = { Text("10-band values") },
            placeholder = { Text("31  12.0\n63  1.1\n125  0.6\n…\n16000  -12.0") },
            minLines = 6, maxLines = 10, modifier = Modifier.fillMaxWidth())
        Text("Values are converted to EQify's eight bands and stored on this device.",
            style = MaterialTheme.typography.bodySmall, color = EqifyTheme.colors.textMuted)
    }
}

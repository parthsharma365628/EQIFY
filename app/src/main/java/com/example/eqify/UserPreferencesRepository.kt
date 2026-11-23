package com.example.eqify

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.net.URLDecoder
import java.net.URLEncoder

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "eqify_prefs")

internal object CustomPresetNameCodec {
    private const val PREFIX = "v2:"

    fun encode(names: List<String>): String = PREFIX + names.joinToString("|") {
        URLEncoder.encode(it, Charsets.UTF_8.name())
    }

    fun decode(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        if (!raw.startsWith(PREFIX)) {
            return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }
        val payload = raw.removePrefix(PREFIX)
        if (payload.isBlank()) return emptyList()
        return payload.split("|").mapNotNull {
            runCatching { URLDecoder.decode(it, Charsets.UTF_8.name()) }
                .getOrNull()
                ?.trim()
                ?.takeIf(String::isNotEmpty)
        }
    }

    fun migrateReservedNames(
        names: List<String>,
        reservedNames: Collection<String>
    ): List<Pair<String, String>> {
        val used = reservedNames.mapTo(mutableSetOf()) { it.lowercase() }
        used.add("custom")

        return names.mapNotNull { original ->
            val cleaned = original.trim()
            if (cleaned.isEmpty()) return@mapNotNull null

            var migrated = cleaned
            var suffix = 2
            if (migrated.lowercase() in used) {
                migrated = "$cleaned (Custom)"
                while (migrated.lowercase() in used) {
                    migrated = "$cleaned (Custom $suffix)"
                    suffix++
                }
            }
            used.add(migrated.lowercase())
            original to migrated
        }
    }
}

class UserPreferencesRepository(private val context: Context) {

    /**
     * A settings flow that emits only when *its* value changes. DataStore's
     * `data` re-emits on every write to any key; without distinctUntilChanged,
     * an unrelated write (e.g. the tile's processing snapshot) restarts every
     * collector, which caused a genre-detection feedback loop (Pop/Rock flicker).
     */
    private fun <T> pref(transform: suspend (Preferences) -> T): Flow<T> =
        context.dataStore.data.map(transform).distinctUntilChanged()

    private object Keys {
        val IS_EQ_ENABLED         = booleanPreferencesKey("is_eq_enabled")
        val AUTO_GENRE_DETECTION  = booleanPreferencesKey("auto_genre_detection")
        val SMOOTH_EQ_TRANSITIONS = booleanPreferencesKey("smooth_eq_transitions")
        val OUTPUT_PROTECTION_MODE = stringPreferencesKey("output_protection_mode")
        // Legacy Boolean retained only for migrating existing installations.
        val LIMIT_OUTPUT_GAIN     = booleanPreferencesKey("limit_output_gain")
        val FORCE_MONO            = booleanPreferencesKey("force_mono")
        val MEDIA_LISTENER        = booleanPreferencesKey("media_listener_enabled")
        val SELECTED_HEADPHONE    = stringPreferencesKey("selected_headphone")
        val CORRECTION_SOURCE = stringPreferencesKey("headphone_correction_source")
        val CUSTOM_CORRECTION_ID = stringPreferencesKey("custom_correction_id")
        val HEADPHONE_AUTO_DETECT = booleanPreferencesKey("headphone_auto_detect")
        val BASS_BOOST_LEVEL      = floatPreferencesKey("bass_boost_level")
        val CUSTOM_PRESET_NAMES   = stringPreferencesKey("custom_preset_names")
        val LAST_PRESET           = stringPreferencesKey("last_preset")
        val PROCESSING_STATUS     = stringPreferencesKey("processing_status")
        val PROCESSING_PROFILE    = stringPreferencesKey("processing_profile")
        val LASTFM_API_KEY        = stringPreferencesKey("lastfm_api_key")
        val FAVORITE_HEADPHONES   = stringPreferencesKey("favorite_headphones")
        val DEVICE_CHOICES        = stringPreferencesKey("device_headphone_choices")
        val GENRE_OVERRIDES       = stringPreferencesKey("genre_curve_overrides")
        val GENRE_STRENGTH        = floatPreferencesKey("genre_strength")
        val DEVICE_PROFILES       = stringPreferencesKey("device_profiles")
        val UPDATE_AUTO_CHECK     = booleanPreferencesKey("update_auto_check")
        val UPDATE_LAST_CHECK     = longPreferencesKey("update_last_check")
        val UPDATE_LATEST_TAG     = stringPreferencesKey("update_latest_tag")
        val UPDATE_LATEST_URL     = stringPreferencesKey("update_latest_url")
        val UPDATE_DISMISSED      = stringPreferencesKey("update_dismissed_tag")
    }

    // ── Standard settings ─────────────────────────────────────────────

    val isEqEnabled: Flow<Boolean> = pref { prefs ->
        prefs[Keys.IS_EQ_ENABLED] ?: true
    }

    val autoGenreDetection: Flow<Boolean> = pref { prefs ->
        prefs[Keys.AUTO_GENRE_DETECTION] ?: true
    }

    val smoothEqTransitions: Flow<Boolean> = pref { prefs ->
        prefs[Keys.SMOOTH_EQ_TRANSITIONS] ?: true
    }

    val outputProtectionMode: Flow<OutputProtectionMode> = pref { prefs ->
        OutputProtectionMode.fromStorageValue(prefs[Keys.OUTPUT_PROTECTION_MODE])
            ?: when (prefs[Keys.LIMIT_OUTPUT_GAIN]) {
                false -> OutputProtectionMode.OFF
                true, null -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    OutputProtectionMode.BALANCED
                } else {
                    OutputProtectionMode.SAFE
                }
            }
    }

    val forceMono: Flow<Boolean> = pref { prefs ->
        prefs[Keys.FORCE_MONO] ?: false
    }

    val mediaListenerEnabled: Flow<Boolean> = pref { prefs ->
        prefs[Keys.MEDIA_LISTENER] ?: true
    }

    val selectedHeadphone: Flow<String> = pref { prefs ->
        prefs[Keys.SELECTED_HEADPHONE] ?: "Sony WH-1000XM5"
    }

    val headphoneCorrectionSelection: Flow<HeadphoneCorrectionSelection> = pref { prefs ->
        when (prefs[Keys.CORRECTION_SOURCE]) {
            "custom" -> prefs[Keys.CUSTOM_CORRECTION_ID]?.takeIf { it.isNotBlank() }
                ?.let { HeadphoneCorrectionSelection.Custom(it) } ?: HeadphoneCorrectionSelection.None
            "none" -> HeadphoneCorrectionSelection.None
            else -> HeadphoneCorrectionSelection.Dataset(
                prefs[Keys.SELECTED_HEADPHONE] ?: "Sony WH-1000XM5"
            )
        }
    }

    val headphoneAutoDetect: Flow<Boolean> = pref { prefs ->
        prefs[Keys.HEADPHONE_AUTO_DETECT] ?: true
    }

    val bassBoostLevel: Flow<Float> = pref { prefs ->
        prefs[Keys.BASS_BOOST_LEVEL] ?: 0f
    }

    val lastPreset: Flow<String> = pref { prefs ->
        prefs[Keys.LAST_PRESET] ?: "Hip-Hop"
    }

    val processingSnapshot: Flow<EqProcessingSnapshot> = pref { prefs ->
        val requestedEnabled = prefs[Keys.IS_EQ_ENABLED] ?: true
        EqProcessingSnapshot(
            requestedEnabled = requestedEnabled,
            status = EqProcessingStatus.fromStorageValue(prefs[Keys.PROCESSING_STATUS])
                ?: if (requestedEnabled) EqProcessingStatus.UNAVAILABLE
                else EqProcessingStatus.PAUSED,
            activeProfile = prefs[Keys.PROCESSING_PROFILE]
                ?: prefs[Keys.LAST_PRESET]
                ?: "Hip-Hop"
        )
    }

    val lastFmApiKey: Flow<String> = pref { prefs ->
        prefs[Keys.LASTFM_API_KEY] ?: ""
    }

    /** What the user chose for each output device (FEATURE_HEADPHONE_DETECTION.md). */
    /** The user's versions of built-in curves, by built-in name (FEATURE_CUSTOM_GENRE_CURVES.md). */
    val genreOverrides: Flow<Map<String, List<Float>>> = pref { prefs ->
        GenreCurveOverrides.decode(prefs[Keys.GENRE_OVERRIDES])
    }

    /**
     * Customise built-in [name], or reset it with null. A curve equal to the
     * built-in removes the override. Flat and unknown names are ignored.
     */
    suspend fun setGenreOverride(name: String, gains: List<Float>?) {
        if (!GenreCurveOverrides.isCustomisable(name)) return
        context.dataStore.edit { prefs ->
            val map = GenreCurveOverrides.decode(prefs[Keys.GENRE_OVERRIDES]).toMutableMap()
            if (gains == null || GenreCurveOverrides.isDefault(name, gains)) map.remove(name)
            else {
                require(GenreCurveOverrides.isValidCurve(gains)) { "Invalid curve." }
                map[name] = gains
            }
            prefs[Keys.GENRE_OVERRIDES] = GenreCurveOverrides.encode(map)
        }
    }

    suspend fun clearGenreOverrides() {
        context.dataStore.edit { it.remove(Keys.GENRE_OVERRIDES) }
    }

    /** Genre strength, 0–125% as 0..1.25 (FEATURES_NEXT_PLAN.md §2). */
    val genreStrength: Flow<Float> = pref { GenreStrength.normalise(it[Keys.GENRE_STRENGTH]) }

    suspend fun setGenreStrength(value: Float) {
        context.dataStore.edit { it[Keys.GENRE_STRENGTH] = GenreStrength.normalise(value) }
    }

    // ── Update check (FEATURES_NEXT_PLAN.md §1) ───────────────────────

    val autoUpdateCheck: Flow<Boolean> = pref { it[Keys.UPDATE_AUTO_CHECK] ?: true }
    val lastUpdateCheck: Flow<Long> = pref { it[Keys.UPDATE_LAST_CHECK] ?: 0L }
    val dismissedUpdateTag: Flow<String> = pref { it[Keys.UPDATE_DISMISSED].orEmpty() }

    /** The newest release seen at the last successful check (may be older than this build). */
    val latestKnownRelease: Flow<ReleaseInfo?> = pref { prefs ->
        val tag = prefs[Keys.UPDATE_LATEST_TAG]
        val url = prefs[Keys.UPDATE_LATEST_URL]
        val version = AppVersion.parse(tag)
        if (tag == null || url == null || version == null || !url.startsWith("https://github.com/")) null
        else ReleaseInfo(tag, version, url)
    }

    suspend fun setAutoUpdateCheck(enabled: Boolean) {
        context.dataStore.edit { it[Keys.UPDATE_AUTO_CHECK] = enabled }
    }

    suspend fun recordUpdateCheck(atMillis: Long, newest: ReleaseInfo?) {
        context.dataStore.edit { prefs ->
            prefs[Keys.UPDATE_LAST_CHECK] = atMillis
            if (newest == null) {
                prefs.remove(Keys.UPDATE_LATEST_TAG); prefs.remove(Keys.UPDATE_LATEST_URL)
            } else {
                prefs[Keys.UPDATE_LATEST_TAG] = newest.tag
                prefs[Keys.UPDATE_LATEST_URL] = newest.url
            }
        }
    }

    suspend fun dismissUpdate(tag: String) {
        context.dataStore.edit { it[Keys.UPDATE_DISMISSED] = tag }
    }

    // ── Per-device sound (FEATURES_NEXT_PLAN.md §4) ───────────────────

    val deviceProfiles: Flow<Map<String, DeviceProfile>> = pref { DeviceProfiles.decode(it[Keys.DEVICE_PROFILES]) }

    private suspend fun editDeviceProfiles(transform: (Map<String, DeviceProfile>) -> Map<String, DeviceProfile>) {
        context.dataStore.edit { prefs ->
            val next = transform(DeviceProfiles.decode(prefs[Keys.DEVICE_PROFILES]))
            prefs[Keys.DEVICE_PROFILES] = DeviceProfiles.encode(next)
        }
    }

    /** Lists the device under My devices; keeps its settings and any rename. */
    suspend fun recordDeviceSeen(key: String, kind: AudioOutputKind, name: String, atMillis: Long) = editDeviceProfiles { map ->
        val existing = map[key]
        map + (key to (existing?.copy(kind = kind, lastSeenMillis = atMillis, label = existing.label ?: name)
            ?: DeviceProfile(label = name, kind = kind, lastSeenMillis = atMillis)))
    }

    suspend fun updateDeviceProfile(key: String, kind: AudioOutputKind, transform: (DeviceProfile) -> DeviceProfile) =
        editDeviceProfiles { map -> map + (key to transform(map[key] ?: DeviceProfile(kind = kind))) }

    /** Removes the device's settings and its remembered headphone correction. */
    suspend fun forgetDevice(key: String) {
        editDeviceProfiles { it - key }
        setDeviceChoice(key, null)
    }

    suspend fun renamePresetInDeviceProfiles(from: String, to: String) =
        editDeviceProfiles { DeviceProfiles.renamePreset(it, from, to) }

    suspend fun removePresetFromDeviceProfiles(name: String) =
        editDeviceProfiles { DeviceProfiles.removePreset(it, name) }

    val deviceChoices: Flow<Map<String, DeviceChoice>> = pref { prefs ->
        DeviceHeadphoneMemory.decode(prefs[Keys.DEVICE_CHOICES])
    }

    /** Remember (or, with null, forget) the choice for one device key. */
    suspend fun setDeviceChoice(deviceKey: String, choice: DeviceChoice?) {
        context.dataStore.edit { prefs ->
            val map = DeviceHeadphoneMemory.decode(prefs[Keys.DEVICE_CHOICES]).toMutableMap()
            if (choice == null) map.remove(deviceKey) else map[deviceKey] = choice
            prefs[Keys.DEVICE_CHOICES] = DeviceHeadphoneMemory.encode(map)
        }
    }

    val favoriteHeadphones: Flow<List<String>> = pref { prefs ->
        CustomPresetNameCodec.decode(prefs[Keys.FAVORITE_HEADPHONES] ?: "")
    }

    // ── Write methods ─────────────────────────────────────────────────

    suspend fun setEqEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.IS_EQ_ENABLED] = enabled }
    }

    suspend fun setAutoGenreDetection(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_GENRE_DETECTION] = enabled }
    }

    suspend fun setSmoothEqTransitions(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SMOOTH_EQ_TRANSITIONS] = enabled }
    }

    suspend fun setOutputProtectionMode(mode: OutputProtectionMode) {
        context.dataStore.edit { prefs ->
            prefs[Keys.OUTPUT_PROTECTION_MODE] = mode.name
            prefs.remove(Keys.LIMIT_OUTPUT_GAIN)
        }
    }

    suspend fun setForceMono(enabled: Boolean) {
        context.dataStore.edit { it[Keys.FORCE_MONO] = enabled }
    }

    suspend fun setMediaListenerEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.MEDIA_LISTENER] = enabled }
    }

    suspend fun setSelectedHeadphone(name: String) {
        context.dataStore.edit {
            it[Keys.SELECTED_HEADPHONE] = name
            it[Keys.CORRECTION_SOURCE] = "dataset"
            it.remove(Keys.CUSTOM_CORRECTION_ID)
        }
    }

    suspend fun selectCustomCorrection(id: String) {
        context.dataStore.edit {
            it[Keys.CORRECTION_SOURCE] = "custom"
            it[Keys.CUSTOM_CORRECTION_ID] = id
        }
    }

    suspend fun selectNoCorrection() {
        context.dataStore.edit {
            it[Keys.CORRECTION_SOURCE] = "none"
            it.remove(Keys.CUSTOM_CORRECTION_ID)
        }
    }

    suspend fun setHeadphoneAutoDetect(enabled: Boolean) {
        context.dataStore.edit { it[Keys.HEADPHONE_AUTO_DETECT] = enabled }
    }

    suspend fun setBassBoostLevel(level: Float) {
        context.dataStore.edit { it[Keys.BASS_BOOST_LEVEL] = level }
    }

    suspend fun setLastPreset(name: String) {
        context.dataStore.edit { it[Keys.LAST_PRESET] = name }
    }

    suspend fun setProcessingSnapshot(status: EqProcessingStatus, activeProfile: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.PROCESSING_STATUS] = status.name
            prefs[Keys.PROCESSING_PROFILE] = activeProfile.ifBlank {
                prefs[Keys.LAST_PRESET] ?: "Flat"
            }
        }
    }

    suspend fun setLastFmApiKey(apiKey: String) {
        context.dataStore.edit { prefs ->
            val cleaned = apiKey.trim()
            if (cleaned.isEmpty()) prefs.remove(Keys.LASTFM_API_KEY)
            else prefs[Keys.LASTFM_API_KEY] = cleaned
        }
    }

    suspend fun toggleFavoriteHeadphone(name: String) {
        context.dataStore.edit { prefs ->
            val favorites = CustomPresetNameCodec
                .decode(prefs[Keys.FAVORITE_HEADPHONES] ?: "")
                .toMutableList()
            val existing = favorites.indexOfFirst { it.equals(name, ignoreCase = true) }
            if (existing >= 0) favorites.removeAt(existing) else favorites.add(name.trim())
            prefs[Keys.FAVORITE_HEADPHONES] = CustomPresetNameCodec.encode(favorites)
        }
    }

    suspend fun migrateLegacyCustomPresets() {
        context.dataStore.edit { prefs ->
            val rawNames = prefs[Keys.CUSTOM_PRESET_NAMES] ?: return@edit
            val originalNames = CustomPresetNameCodec.decode(rawNames)
            val migrations = CustomPresetNameCodec.migrateReservedNames(
                originalNames,
                EqProfileManager.defaultProfiles.keys
            )
            val migratedNames = migrations.map { it.second }

            migrations.forEach { (original, migrated) ->
                if (original == migrated) return@forEach
                val oldKey = stringPreferencesKey("custom_gains_$original")
                val newKey = stringPreferencesKey("custom_gains_$migrated")
                prefs[oldKey]?.let { gains ->
                    prefs[newKey] = gains
                    prefs.remove(oldKey)
                }
                if (prefs[Keys.LAST_PRESET].equals(original, ignoreCase = true)) {
                    prefs[Keys.LAST_PRESET] = migrated
                }
            }

            val encoded = CustomPresetNameCodec.encode(migratedNames)
            if (encoded != rawNames) prefs[Keys.CUSTOM_PRESET_NAMES] = encoded
        }
    }

    // ── Custom preset names list ──────────────────────────────────────

    val customPresetNames: Flow<List<String>> = pref { prefs ->
        val raw = prefs[Keys.CUSTOM_PRESET_NAMES] ?: ""
        CustomPresetNameCodec.decode(raw)
    }

    private suspend fun saveCustomPresetNamesList(names: List<String>) {
        context.dataStore.edit { it[Keys.CUSTOM_PRESET_NAMES] = CustomPresetNameCodec.encode(names) }
    }

    /**
     * Every saved custom preset's gains, by name. Lists (not FloatArrays) so
     * distinctUntilChanged compares by value. Used by the EQ service when a
     * song's correction points at a preset.
     */
    val customPresetGains: Flow<Map<String, List<Float>>> = pref { prefs ->
        CustomPresetNameCodec.decode(prefs[Keys.CUSTOM_PRESET_NAMES] ?: "").mapNotNull { name ->
            val gains = prefs[stringPreferencesKey("custom_gains_$name")]
                ?.split(",")?.mapNotNull { it.trim().toFloatOrNull() }
            if (gains != null && gains.size == 8 && gains.all { it.isFinite() }) name to gains else null
        }.toMap()
    }

    // ── Custom preset gains ───────────────────────────────────────────

    suspend fun saveCustomPreset(presetName: String, gains: FloatArray) {
        val gainsKey = stringPreferencesKey("custom_gains_$presetName")
        context.dataStore.edit { prefs ->
            prefs[gainsKey] = gains.joinToString(",")
        }
        val current = customPresetNames.first().toMutableList()
        if (current.none { it.equals(presetName, ignoreCase = true) }) {
            current.add(presetName)
            saveCustomPresetNamesList(current)
        }
    }

    /**
     * Adds a new preset in one atomic edit. Unlike [saveCustomPreset], never
     * overwrites: fails if the name is reserved or already used (case-insensitive).
     */
    suspend fun saveNewCustomPreset(presetName: String, gains: FloatArray) {
        val name = presetName.trim()
        require(gains.size == 8 && gains.all { it.isFinite() && it in -12f..12f }) { "Invalid preset gains." }
        context.dataStore.edit { prefs ->
            val names = CustomPresetNameCodec.decode(prefs[Keys.CUSTOM_PRESET_NAMES] ?: "")
            PresetNameRules.validateNew(name, names)?.let { throw IllegalArgumentException(it) }
            prefs[stringPreferencesKey("custom_gains_$name")] = gains.joinToString(",")
            prefs[Keys.CUSTOM_PRESET_NAMES] = CustomPresetNameCodec.encode(names + name)
        }
    }

    suspend fun loadCustomPresetGains(presetName: String): FloatArray? {
        val gainsKey = stringPreferencesKey("custom_gains_$presetName")
        val prefs = context.dataStore.data.first()
        val raw = prefs[gainsKey] ?: return null
        val gains = raw.split(",").mapNotNull { it.trim().toFloatOrNull() }.toFloatArray()
        return if (gains.size == 8) gains else null
    }

    suspend fun deleteCustomPreset(presetName: String) {
        val gainsKey = stringPreferencesKey("custom_gains_$presetName")
        context.dataStore.edit { prefs ->
            prefs.remove(gainsKey)
        }
        val current = customPresetNames.first().toMutableList()
        current.removeAll { it.equals(presetName, ignoreCase = true) }
        saveCustomPresetNamesList(current)
    }

    suspend fun renameCustomPreset(oldName: String, newName: String) {
        val name = newName.trim()
        require(name.isNotEmpty()) { "Enter a preset name." }
        require(!name.equals("Custom", true) &&
            EqProfileManager.defaultProfiles.keys.none { it.equals(name, true) }) {
            "This name is reserved."
        }
        context.dataStore.edit { prefs ->
            val names = CustomPresetNameCodec.decode(prefs[Keys.CUSTOM_PRESET_NAMES] ?: "")
            require(oldName in names) { "This preset no longer exists." }
            require(names.none { it != oldName && it.equals(name, true) }) {
                "A preset with this name already exists."
            }
            val oldKey = stringPreferencesKey("custom_gains_$oldName")
            val gains = requireNotNull(prefs[oldKey]) { "This preset's saved values are missing." }
            prefs.remove(oldKey)
            prefs[stringPreferencesKey("custom_gains_$name")] = gains
            prefs[Keys.CUSTOM_PRESET_NAMES] = CustomPresetNameCodec.encode(
                names.map { if (it == oldName) name else it }
            )
            if (prefs[Keys.LAST_PRESET] == oldName) prefs[Keys.LAST_PRESET] = name
        }
    }

    suspend fun loadAllCustomPresets(): Map<String, FloatArray> {
        val names = customPresetNames.first()
        val result = mutableMapOf<String, FloatArray>()
        for (name in names) {
            val gains = loadCustomPresetGains(name)
            if (gains != null) result[name] = gains
        }
        return result
    }

}

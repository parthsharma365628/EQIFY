package com.example.eqify

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
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

    private object Keys {
        val IS_EQ_ENABLED         = booleanPreferencesKey("is_eq_enabled")
        val AUTO_GENRE_DETECTION  = booleanPreferencesKey("auto_genre_detection")
        val LIMIT_OUTPUT_GAIN     = booleanPreferencesKey("limit_output_gain")
        val FORCE_MONO            = booleanPreferencesKey("force_mono")
        val MEDIA_LISTENER        = booleanPreferencesKey("media_listener_enabled")
        val SELECTED_HEADPHONE    = stringPreferencesKey("selected_headphone")
        val HEADPHONE_AUTO_DETECT = booleanPreferencesKey("headphone_auto_detect")
        val BASS_BOOST_LEVEL      = floatPreferencesKey("bass_boost_level")
        val CUSTOM_PRESET_NAMES   = stringPreferencesKey("custom_preset_names")
        val LAST_PRESET           = stringPreferencesKey("last_preset")
        val LASTFM_API_KEY        = stringPreferencesKey("lastfm_api_key")
        val FAVORITE_HEADPHONES   = stringPreferencesKey("favorite_headphones")
    }

    // ── Standard settings ─────────────────────────────────────────────

    val isEqEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.IS_EQ_ENABLED] ?: true
    }

    val autoGenreDetection: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.AUTO_GENRE_DETECTION] ?: true
    }

    val limitOutputGain: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.LIMIT_OUTPUT_GAIN] ?: true
    }

    val forceMono: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.FORCE_MONO] ?: false
    }

    val mediaListenerEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.MEDIA_LISTENER] ?: true
    }

    val selectedHeadphone: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.SELECTED_HEADPHONE] ?: "Sony WH-1000XM5"
    }

    val headphoneAutoDetect: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.HEADPHONE_AUTO_DETECT] ?: true
    }

    val bassBoostLevel: Flow<Float> = context.dataStore.data.map { prefs ->
        prefs[Keys.BASS_BOOST_LEVEL] ?: 0f
    }

    val lastPreset: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.LAST_PRESET] ?: "Hip-Hop"
    }

    val lastFmApiKey: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.LASTFM_API_KEY] ?: ""
    }

    val favoriteHeadphones: Flow<List<String>> = context.dataStore.data.map { prefs ->
        CustomPresetNameCodec.decode(prefs[Keys.FAVORITE_HEADPHONES] ?: "")
    }

    // ── Write methods ─────────────────────────────────────────────────

    suspend fun setEqEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.IS_EQ_ENABLED] = enabled }
    }

    suspend fun setAutoGenreDetection(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_GENRE_DETECTION] = enabled }
    }

    suspend fun setLimitOutputGain(enabled: Boolean) {
        context.dataStore.edit { it[Keys.LIMIT_OUTPUT_GAIN] = enabled }
    }

    suspend fun setForceMono(enabled: Boolean) {
        context.dataStore.edit { it[Keys.FORCE_MONO] = enabled }
    }

    suspend fun setMediaListenerEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.MEDIA_LISTENER] = enabled }
    }

    suspend fun setSelectedHeadphone(name: String) {
        context.dataStore.edit { it[Keys.SELECTED_HEADPHONE] = name }
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

    val customPresetNames: Flow<List<String>> = context.dataStore.data.map { prefs ->
        val raw = prefs[Keys.CUSTOM_PRESET_NAMES] ?: ""
        CustomPresetNameCodec.decode(raw)
    }

    private suspend fun saveCustomPresetNamesList(names: List<String>) {
        context.dataStore.edit { it[Keys.CUSTOM_PRESET_NAMES] = CustomPresetNameCodec.encode(names) }
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

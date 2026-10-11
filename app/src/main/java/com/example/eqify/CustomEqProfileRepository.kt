package com.example.eqify

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class CustomEqProfile(
    val id: String,
    val displayName: String,
    val sourceFileName: String,
    val importedAtEpochMillis: Long,
    val gains: List<Float>
)

/** Small, local-only profile store. Never mixed with the downloaded Pages cache. */
class CustomEqProfileRepository(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "custom_eq_profiles.json"))
    private val lock = Any()
    private val _profiles = MutableStateFlow<List<CustomEqProfile>>(emptyList())
    val profiles = _profiles.asStateFlow()
    private val _loadError = MutableStateFlow<String?>(null)
    val loadError = _loadError.asStateFlow()

    init {
        try { _profiles.value = read() }
        catch (e: Exception) { _loadError.value = "Custom profiles could not be read: ${e.message}" }
    }

    fun find(id: String): CustomEqProfile? = _profiles.value.firstOrNull { it.id == id }

    fun importProfile(name: String, sourceFileName: String, gains: FloatArray): CustomEqProfile = synchronized(lock) {
        ensureReadable()
        require(gains.size == 8 && gains.all { it.isFinite() && it in -12f..12f })
        val profile = CustomEqProfile(UUID.randomUUID().toString(), name.trim().take(100).ifBlank { "Imported EQ" },
            sourceFileName.take(200), System.currentTimeMillis(), gains.toList())
        save(_profiles.value + profile)
        profile
    }

    fun rename(id: String, name: String) = synchronized(lock) {
        ensureReadable()
        val trimmed = name.trim()
        require(trimmed.isNotEmpty() && trimmed.length <= 100) { "Name must be 1–100 characters." }
        require(find(id) != null) { "Profile no longer exists." }
        save(_profiles.value.map { if (it.id == id) it.copy(displayName = trimmed) else it })
    }

    fun delete(id: String) = synchronized(lock) {
        ensureReadable()
        save(_profiles.value.filterNot { it.id == id })
    }

    fun clear() = synchronized(lock) {
        ensureReadable()
        save(emptyList())
    }

    private fun ensureReadable() { check(_loadError.value == null) { _loadError.value ?: "Custom store unavailable" } }

    private fun read(): List<CustomEqProfile> {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return emptyList()
        val root = JSONObject(file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
        require(root.getInt("schemaVersion") == 1) { "Unsupported custom profile schema" }
        val items = root.getJSONArray("profiles")
        return List(items.length()) { i ->
            val item = items.getJSONObject(i)
            require(item.getString("format") == "graphic_eq") { "Unsupported custom profile format" }
            val gainsJson = item.getJSONArray("gains")
            val frequencies = item.getJSONArray("frequenciesHz")
            require(gainsJson.length() == 8 && frequencies.length() == 8)
            require((0 until 8).all { frequencies.getInt(it) == EqProfileManager.BAND_CENTERS_HZ[it] })
            val gains = (0 until 8).map { gainsJson.getDouble(it).toFloat() }
            require(gains.all { it.isFinite() && it in -12f..12f })
            CustomEqProfile(item.getString("id"), item.getString("displayName"),
                item.getString("sourceFileName"), item.getLong("importedAtEpochMillis"), gains)
        }
    }

    private fun save(profiles: List<CustomEqProfile>) {
        val root = JSONObject().put("schemaVersion", 1).put("profiles", JSONArray().apply {
            profiles.forEach { profile ->
                put(JSONObject().put("id", profile.id).put("displayName", profile.displayName)
                    .put("sourceFileName", profile.sourceFileName).put("format", "graphic_eq")
                    .put("importedAtEpochMillis", profile.importedAtEpochMillis)
                    .put("frequenciesHz", JSONArray(EqProfileManager.BAND_CENTERS_HZ.toList()))
                    .put("gains", JSONArray(profile.gains)))
            }
        })
        val stream = file.startWrite()
        try {
            stream.write(root.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
            _profiles.value = profiles
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
    }
}

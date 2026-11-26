package com.example.eqify

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Everything a backup carries (FEATURES_BATCH2_PLAN.md §3). Listening history,
 * update-check state, crash-report choices and the Last.fm key are never included.
 */
data class BackupData(
    /** Whitelisted settings by DataStore key: Boolean, Float or String values only. */
    val settings: Map<String, Any> = emptyMap(),
    /** User presets in list order. */
    val customPresets: List<Pair<String, List<Float>>> = emptyList(),
    val genreOverrides: Map<String, List<Float>> = emptyMap(),
    val deviceProfiles: Map<String, DeviceProfile> = emptyMap(),
    val deviceChoices: Map<String, DeviceChoice> = emptyMap(),
    val favoriteHeadphones: List<String> = emptyList(),
    val importedCorrections: List<CustomEqProfile> = emptyList(),
    val favoritePresets: List<String> = emptyList()
) {
    val isEmpty: Boolean get() = settings.isEmpty() && customPresets.isEmpty() && genreOverrides.isEmpty() &&
        deviceProfiles.isEmpty() && deviceChoices.isEmpty() && favoriteHeadphones.isEmpty() &&
        importedCorrections.isEmpty() && favoritePresets.isEmpty()

    /** For example "6 presets, 3 genre curves, 2 devices, settings". */
    fun summary(): String {
        val parts = mutableListOf<String>()
        fun add(n: Int, one: String, many: String) { if (n > 0) parts += "$n ${if (n == 1) one else many}" }
        add(customPresets.size, "preset", "presets")
        add(genreOverrides.size, "genre curve", "genre curves")
        add(deviceProfiles.size, "device", "devices")
        add(importedCorrections.size, "imported correction", "imported corrections")
        if (settings.isNotEmpty()) parts += "settings"
        return parts.joinToString(", ").ifEmpty { "nothing" }
    }
}

object BackupCodec {
    const val FORMAT = "eqify-backup"
    const val VERSION = 1
    const val MAX_BYTES = 2 * 1024 * 1024

    enum class Type { BOOLEAN, FLOAT, STRING }

    /** The settings a backup may carry, and nothing else. */
    val SETTINGS: Map<String, Type> = linkedMapOf(
        "auto_genre_detection" to Type.BOOLEAN,
        "smooth_eq_transitions" to Type.BOOLEAN,
        "output_protection_mode" to Type.STRING,
        "force_mono" to Type.BOOLEAN,
        "headphone_auto_detect" to Type.BOOLEAN,
        "bass_boost_level" to Type.FLOAT,
        "genre_strength" to Type.FLOAT,
        "volume_aware_bass" to Type.BOOLEAN,
        "notification_bass_button" to Type.BOOLEAN,
        "loudness_compensation" to Type.BOOLEAN,
        "volume_cap_enabled" to Type.BOOLEAN,
        "volume_cap_level" to Type.FLOAT,
        "balance" to Type.FLOAT,
        "haptics" to Type.BOOLEAN,
        "safe_listening_enabled" to Type.BOOLEAN,
        "safe_listening_mode" to Type.STRING,
        "device_volume_memory" to Type.BOOLEAN,
        "update_auto_check" to Type.BOOLEAN,
        "selected_headphone" to Type.STRING,
        "headphone_correction_source" to Type.STRING,
        "custom_correction_id" to Type.STRING,
        "last_preset" to Type.STRING
    )

    sealed interface Result {
        data class Ok(val data: BackupData, val appVersion: String?, val createdAt: Long?) : Result
        data class Error(val message: String) : Result
    }

    fun fileName(dateIso: String) = "EQify-backup-$dateIso.json"

    fun build(data: BackupData, appVersion: String, createdAt: Long): String {
        val root = JsonObject()
        root.addProperty("format", FORMAT)
        root.addProperty("version", VERSION)
        root.addProperty("appVersion", appVersion)
        root.addProperty("createdAt", createdAt)
        root.add("settings", JsonObject().apply {
            data.settings.forEach { (k, v) ->
                when (v) {
                    is Boolean -> addProperty(k, v)
                    is Number -> addProperty(k, v)
                    is String -> addProperty(k, v)
                }
            }
        })
        root.add("customPresets", JsonArray().apply {
            data.customPresets.forEach { (name, gains) ->
                add(JsonObject().apply { addProperty("name", name); add("gains", gainsJson(gains)) })
            }
        })
        root.add("genreCurves", JsonObject().apply {
            data.genreOverrides.forEach { (k, v) -> add(k, gainsJson(v)) }
        })
        root.addProperty("deviceProfiles", DeviceProfiles.encode(data.deviceProfiles))
        root.addProperty("deviceHeadphoneChoices", DeviceHeadphoneMemory.encode(data.deviceChoices))
        root.add("favoriteHeadphones", JsonArray().apply { data.favoriteHeadphones.forEach { add(it) } })
        root.add("favoritePresets", JsonArray().apply { data.favoritePresets.forEach { add(it) } })
        root.add("importedCorrections", JsonArray().apply {
            data.importedCorrections.forEach { p ->
                add(JsonObject().apply {
                    addProperty("id", p.id)
                    addProperty("displayName", p.displayName)
                    addProperty("sourceFileName", p.sourceFileName)
                    addProperty("importedAt", p.importedAtEpochMillis)
                    add("gains", gainsJson(p.gains))
                })
            }
        })
        return root.toString()
    }

    /** Validates the whole file first, so a bad file never partly applies. Unknown keys are ignored. */
    fun parse(text: String): Result {
        if (text.length > MAX_BYTES) return Result.Error("This file is too large to be an EQify backup.")
        val root = try {
            @Suppress("DEPRECATION")
            JsonParser().parse(text).takeIf { it.isJsonObject }?.asJsonObject
        } catch (e: Exception) { null } ?: return Result.Error(NOT_A_BACKUP)
        return try {
            if (root.str("format") != FORMAT) return Result.Error(NOT_A_BACKUP)
            val version = root.get("version")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
                ?: return Result.Error("This backup has no version.")
            if (version > VERSION) return Result.Error("This backup was made by a newer EQify. Update EQify first.")
            if (version < 1) return Result.Error("This backup's version isn't supported.")
            val createdAt = root.get("createdAt")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong
            Result.Ok(readData(root), root.str("appVersion"), createdAt)
        } catch (e: BadBackup) {
            Result.Error(e.message ?: DAMAGED)
        } catch (e: Exception) {
            Result.Error(DAMAGED)
        }
    }

    private const val NOT_A_BACKUP = "This isn't an EQify backup file."
    private const val DAMAGED = "This backup is damaged."
    private const val BAD_SETTING = "A setting in this backup is damaged."

    private class BadBackup(message: String) : Exception(message)

    private fun readData(root: JsonObject): BackupData {
        val settings = linkedMapOf<String, Any>()
        root.obj("settings")?.entrySet()?.forEach { (key, value) ->
            val type = SETTINGS[key] ?: return@forEach      // unknown setting: ignored
            if (!value.isJsonPrimitive) throw BadBackup(BAD_SETTING)
            val p = value.asJsonPrimitive
            val parsed: Any = when (type) {
                Type.BOOLEAN -> if (p.isBoolean) p.asBoolean else throw BadBackup(BAD_SETTING)
                Type.FLOAT -> if (p.isNumber && p.asFloat.isFinite()) p.asFloat else throw BadBackup(BAD_SETTING)
                Type.STRING -> if (p.isString) p.asString.take(200) else throw BadBackup(BAD_SETTING)
            }
            normaliseSetting(key, parsed)?.let { settings[key] = it }
        }

        val presets = mutableListOf<Pair<String, List<Float>>>()
        root.arr("customPresets")?.forEach { el ->
            val o = el.takeIf { it.isJsonObject }?.asJsonObject ?: throw BadBackup("A preset in this backup is damaged.")
            val name = o.str("name")?.trim().orEmpty()
            if (name.isEmpty() || name.length > 100)
                throw BadBackup("A preset in this backup has an invalid name.")
            if (presets.any { it.first.equals(name, true) })
                throw BadBackup("This backup has two presets named \"$name\".")
            val g = gains(o.get("gains"), "preset \"$name\"")
            // A name that is now a built-in (for example Dialogue) keeps the preset under a new name.
            val safeName = if (PresetNameRules.isReserved(name)) PresetNameRules.uniqueName("$name (Custom)", presets.map { it.first }) else name
            presets += safeName to g
        }

        val overrides = linkedMapOf<String, List<Float>>()
        root.obj("genreCurves")?.entrySet()?.forEach { (name, value) ->
            if (!GenreCurveOverrides.isCustomisable(name)) return@forEach   // unknown genre: ignored
            val g = gains(value, "genre curve \"$name\"")
            if (!GenreCurveOverrides.isValidCurve(g)) throw BadBackup("The genre curve \"$name\" is damaged.")
            overrides[name] = g
        }

        val favoritePresets = root.arr("favoritePresets")?.mapNotNull { el ->
            el.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.trim()
                ?.takeIf { it.isNotEmpty() && it.length <= 100 }
        }?.distinctBy { it.lowercase() }.orEmpty()

        val favorites = root.arr("favoriteHeadphones")?.mapNotNull { el ->
            el.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.trim()
                ?.takeIf { it.isNotEmpty() && it.length <= 200 }
        }?.distinct().orEmpty()

        val corrections = mutableListOf<CustomEqProfile>()
        root.arr("importedCorrections")?.forEach { el ->
            val o = el.takeIf { it.isJsonObject }?.asJsonObject
                ?: throw BadBackup("An imported correction in this backup is damaged.")
            val id = o.str("id")?.takeIf { it.isNotBlank() && it.length <= 100 }
                ?: throw BadBackup("An imported correction in this backup is damaged.")
            if (corrections.any { it.id == id }) return@forEach
            val importedAt = o.get("importedAt")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong
            corrections += CustomEqProfile(
                id = id,
                displayName = o.str("displayName")?.trim()?.take(100)?.ifBlank { null } ?: "Imported EQ",
                sourceFileName = o.str("sourceFileName")?.take(200).orEmpty(),
                importedAtEpochMillis = importedAt?.coerceAtLeast(0L) ?: 0L,
                gains = gains(o.get("gains"), "imported correction")
            )
        }

        return BackupData(
            settings = settings,
            customPresets = presets,
            genreOverrides = overrides,
            deviceProfiles = DeviceProfiles.decode(root.str("deviceProfiles")),
            deviceChoices = DeviceHeadphoneMemory.decode(root.str("deviceHeadphoneChoices")),
            favoriteHeadphones = favorites,
            importedCorrections = corrections,
            favoritePresets = favoritePresets
        )
    }

    /** Clamps or rejects one setting; null drops it, so the current value is kept. */
    fun normaliseSetting(key: String, value: Any): Any? = when (key) {
        "bass_boost_level" -> DeviceProfiles.normaliseBass(value as Float)
        "genre_strength" -> GenreStrength.normalise(value as Float)
        "volume_cap_level" -> VolumeCap.normalise(value as Float)
        "balance" -> StereoBalance.normalise(value as Float)
        "safe_listening_mode" -> SafeListening.Mode.entries.firstOrNull { it.name == value }?.name
        "output_protection_mode" -> OutputProtectionMode.fromStorageValue(value as String)?.name
        "headphone_correction_source" -> (value as String).takeIf { it in setOf("dataset", "custom", "none") }
        "selected_headphone", "custom_correction_id", "last_preset" -> (value as String).trim().takeIf { it.isNotEmpty() }
        else -> value
    }

    /**
     * Merge: current settings stay; the backup's presets, genre curves, devices and
     * corrections are added. Where both have one, the current one wins, except a
     * preset with the same name but different gains, which is added with a suffix.
     */
    fun merge(current: BackupData, incoming: BackupData): BackupData {
        val presets = current.customPresets.toMutableList()
        incoming.customPresets.forEach { (name, gains) ->
            val same = presets.firstOrNull { it.first.equals(name, true) }
            when {
                same == null -> presets += name to gains
                same.second == gains -> Unit
                else -> presets += PresetNameRules.uniqueName(name, presets.map { it.first }) to gains
            }
        }
        return current.copy(
            customPresets = presets,
            genreOverrides = incoming.genreOverrides + current.genreOverrides,
            deviceProfiles = incoming.deviceProfiles + current.deviceProfiles,
            deviceChoices = incoming.deviceChoices + current.deviceChoices,
            favoriteHeadphones = (current.favoriteHeadphones + incoming.favoriteHeadphones).distinct(),
            favoritePresets = (current.favoritePresets + incoming.favoritePresets).distinctBy { it.lowercase() },
            importedCorrections = current.importedCorrections +
                incoming.importedCorrections.filter { inc -> current.importedCorrections.none { it.id == inc.id } }
        )
    }

    private fun gains(el: JsonElement?, what: String): List<Float> {
        val a = el?.takeIf { it.isJsonArray }?.asJsonArray ?: throw BadBackup("The $what in this backup is damaged.")
        if (a.size() != 8) throw BadBackup("The $what in this backup is damaged.")
        val g = a.map { v ->
            if (!v.isJsonPrimitive || !v.asJsonPrimitive.isNumber) throw BadBackup("The $what in this backup is damaged.")
            v.asFloat
        }
        if (!g.all { it.isFinite() && it in -12f..12f }) throw BadBackup("The $what in this backup is out of range.")
        return g
    }

    private fun gainsJson(g: List<Float>) = JsonArray().apply { g.forEach { add(it) } }
    private fun JsonObject.str(k: String): String? =
        get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    private fun JsonObject.obj(k: String): JsonObject? = get(k)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.arr(k: String): JsonArray? = get(k)?.takeIf { it.isJsonArray }?.asJsonArray
}

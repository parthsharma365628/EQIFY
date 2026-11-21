package com.example.eqify

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.math.roundToInt

/** A shareable EQ setup: tone curve, optional headphone correction, optional bass. */
data class SharedEqProfile(
    val name: String,
    val tone: List<Float>,
    val correction: SharedCorrection? = null,
    val bass: Float? = null
)

data class SharedCorrection(
    val kind: Kind,
    val name: String,
    val gains: List<Float>
) {
    enum class Kind(val wireValue: String) { DATASET("dataset"), CUSTOM("custom") }
}

class ProfileShareException(val reason: Reason, message: String) : Exception(message) {
    enum class Reason { NOT_EQIFY, CORRUPTED, NEWER_VERSION, INVALID_DATA, TOO_LARGE }
}

/**
 * Encodes/decodes shareable profiles. Pure Kotlin + Gson + java.util so it is
 * JVM-testable (do not use org.json here; it is an Android stub in unit tests).
 *
 * Code format: `EQIFY1:` + Base64-URL (no padding) of raw-Deflate UTF-8 JSON.
 * Plain JSON (as written to `.eqify.json` files) is accepted by [decode] too.
 */
object EqProfileCodec {
    const val PREFIX = "EQIFY1:"
    /** Deep link handled by MainActivity; the code is URL-safe so it is appended as-is. */
    const val LINK_PREFIX = "eqify://import?p="
    const val SCHEMA_VERSION = 1
    const val MAX_INPUT_CHARS = 8 * 1024
    const val MAX_JSON_BYTES = 8 * 1024
    const val MAX_PRESET_NAME = PresetNameRules.MAX_LENGTH
    const val MAX_CORRECTION_NAME = 100
    const val MAX_BASS_DB = 12f
    const val FALLBACK_NAME = "Shared profile"
    private const val MIN_GAIN = -12f
    private const val MAX_GAIN = 12f

    private val codeChars = Regex("[A-Za-z0-9_-]+")
    private val encodedPrefix = Regex("EQIFY1%3A", RegexOption.IGNORE_CASE)

    fun encode(profile: SharedEqProfile): String {
        val json = toJsonObject(profile).toString().toByteArray(Charsets.UTF_8)
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(deflate(json))
    }

    /** `eqify://import?p=EQIFY1:…`. [decode] accepts the link text directly. */
    fun importLink(code: String): String {
        require(code.startsWith(PREFIX)) { "Not a profile code" }
        return LINK_PREFIX + code
    }

    /** Human-readable JSON for files. [decode] accepts it unchanged. */
    fun encodeJson(profile: SharedEqProfile): String =
        GsonBuilder().setPrettyPrinting().create().toJson(toJsonObject(profile))

    /** Parses a code, text containing a code, or plain JSON. Throws [ProfileShareException]. */
    fun decode(input: String): SharedEqProfile {
        if (input.length > MAX_INPUT_CHARS) fail(ProfileShareException.Reason.TOO_LARGE, "This profile is too large.")
        val raw = input.removePrefix("﻿").trim()
        // A link re-encoded by another app may carry "EQIFY1%3A"; Base64-URL itself needs no escaping.
        val text = if (raw.contains(PREFIX)) raw else raw.replace(encodedPrefix, PREFIX)
        val json: String = when {
            text.startsWith("{") -> {
                if (text.toByteArray(Charsets.UTF_8).size > MAX_JSON_BYTES)
                    fail(ProfileShareException.Reason.TOO_LARGE, "This profile is too large.")
                text
            }
            else -> {
                // Use the last code so a profile name cannot inject an earlier one.
                val at = text.lastIndexOf(PREFIX)
                if (at < 0) fail(ProfileShareException.Reason.NOT_EQIFY, "This is not an EQify profile code.")
                val payload = codeChars.matchAt(text, at + PREFIX.length)?.value
                    ?: fail(ProfileShareException.Reason.CORRUPTED, "The profile code is incomplete.")
                val bytes = try {
                    Base64.getUrlDecoder().decode(payload)
                } catch (e: IllegalArgumentException) {
                    fail(ProfileShareException.Reason.CORRUPTED, "The profile code is damaged.")
                }
                String(inflate(bytes), Charsets.UTF_8)
            }
        }
        val root = try {
            @Suppress("DEPRECATION")
            JsonParser().parse(json)
        } catch (e: Exception) {
            fail(ProfileShareException.Reason.CORRUPTED, "The profile data is damaged.")
        }
        return fromJson(root)
    }

    /** Rounds to 0.1 dB, matching the dataset convention. */
    fun round01(value: Float): Float = (value * 10f).roundToInt() / 10f

    /** Trims, collapses whitespace, strips control characters, then caps length. */
    fun sanitizeName(raw: String, maxLength: Int): String =
        raw.filterNot { Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() }
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(maxLength)
            .trim()

    // ── JSON mapping ──────────────────────────────────────────────────

    private fun toJsonObject(profile: SharedEqProfile): JsonObject {
        requireGains(profile.tone, "tone")
        profile.correction?.let { requireGains(it.gains, "correction") }
        profile.bass?.let { require(it.isFinite() && it in 0f..MAX_BASS_DB) { "Invalid bass level" } }
        return JsonObject().apply {
            addProperty("app", "eqify")
            addProperty("v", SCHEMA_VERSION)
            addProperty("name", sanitizeName(profile.name, MAX_PRESET_NAME).ifEmpty { FALLBACK_NAME })
            add("freqs", JsonArray().apply { EqProfileManager.BAND_CENTERS_HZ.forEach { add(it) } })
            add("tone", gainsArray(profile.tone))
            profile.correction?.let { c ->
                add("correction", JsonObject().apply {
                    addProperty("kind", c.kind.wireValue)
                    addProperty("name", sanitizeName(c.name, MAX_CORRECTION_NAME).ifEmpty { FALLBACK_NAME })
                    add("gains", gainsArray(c.gains))
                })
            }
            profile.bass?.let { addProperty("bass", (it * 10f).roundToInt() / 10.0) }
        }
    }

    private fun gainsArray(gains: List<Float>) = JsonArray().apply {
        // Round in double space so 0.1f is written as 0.1, not 0.10000000149.
        gains.forEach { add((it * 10f).roundToInt() / 10.0) }
    }

    private fun requireGains(gains: List<Float>, label: String) {
        require(gains.size == EqProfileManager.BAND_CENTERS_HZ.size) { "$label needs 8 gains" }
        require(gains.all { it.isFinite() && it in MIN_GAIN..MAX_GAIN }) { "$label gains out of range" }
    }

    private fun fromJson(root: JsonElement): SharedEqProfile {
        if (!root.isJsonObject) notEqify()
        val obj = root.asJsonObject
        if (stringOrNull(obj, "app") != "eqify") notEqify()

        val version = obj.get("v")
        if (version == null || !version.isJsonPrimitive || !version.asJsonPrimitive.isNumber) invalid("Missing format version.")
        val v = version.asDouble
        if (v != Math.floor(v)) invalid("Invalid format version.")
        if (v > SCHEMA_VERSION) fail(ProfileShareException.Reason.NEWER_VERSION,
            "This profile was made with a newer version of EQify. Update the app to import it.")
        if (v < SCHEMA_VERSION) invalid("Unsupported format version.")

        val freqs = obj.get("freqs")
        if (freqs == null || !freqs.isJsonArray) invalid("Missing band frequencies.")
        val freqList = freqs.asJsonArray.map { numberOrNull(it) }
        if (freqList.size != EqProfileManager.BAND_CENTERS_HZ.size ||
            freqList.indices.any { freqList[it] != EqProfileManager.BAND_CENTERS_HZ[it].toDouble() }
        ) invalid("Band frequencies do not match EQify's eight bands.")

        val tone = parseGains(obj.get("tone"), "Tone")
        val name = sanitizeName(stringOrNull(obj, "name") ?: "", MAX_PRESET_NAME).ifEmpty { FALLBACK_NAME }

        val correction = obj.get("correction")?.takeUnless { it.isJsonNull }?.let { element ->
            if (!element.isJsonObject) invalid("Invalid headphone correction.")
            val c = element.asJsonObject
            val kind = SharedCorrection.Kind.entries.firstOrNull { it.wireValue == stringOrNull(c, "kind") }
                ?: invalid("Unknown headphone correction type.")
            SharedCorrection(
                kind = kind,
                name = sanitizeName(stringOrNull(c, "name") ?: "", MAX_CORRECTION_NAME).ifEmpty { FALLBACK_NAME },
                gains = parseGains(c.get("gains"), "Headphone correction")
            )
        }

        val bass = obj.get("bass")?.takeUnless { it.isJsonNull }?.let { element ->
            val value = numberOrNull(element) ?: invalid("Invalid bass level.")
            if (!value.isFinite() || value < 0.0 || value > MAX_BASS_DB) invalid("Bass level is out of range.")
            round01(value.toFloat())
        }

        return SharedEqProfile(name, tone, correction, bass)
    }

    private fun parseGains(element: JsonElement?, label: String): List<Float> {
        if (element == null || !element.isJsonArray) invalid("$label gains are missing.")
        val array = element.asJsonArray
        if (array.size() != EqProfileManager.BAND_CENTERS_HZ.size) invalid("$label must have exactly 8 bands.")
        return array.mapIndexed { i, item ->
            val value = numberOrNull(item) ?: invalid("$label band ${i + 1} is not a number.")
            if (!value.isFinite() || value < MIN_GAIN || value > MAX_GAIN)
                invalid("$label band ${i + 1} is outside ±12 dB.")
            round01(value.toFloat())
        }
    }

    private fun numberOrNull(element: JsonElement): Double? =
        if (element.isJsonPrimitive && element.asJsonPrimitive.isNumber)
            runCatching { element.asDouble }.getOrNull()
        else null

    private fun stringOrNull(obj: JsonObject, key: String): String? {
        val e = obj.get(key) ?: return null
        return if (e.isJsonPrimitive && e.asJsonPrimitive.isString) e.asString else null
    }

    // ── Compression ───────────────────────────────────────────────────

    private fun deflate(input: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        try {
            deflater.setInput(input)
            deflater.finish()
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(512)
            while (!deflater.finished()) {
                val n = deflater.deflate(buffer)
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun inflate(input: ByteArray): ByteArray {
        val inflater = Inflater(true)
        try {
            // nowrap mode may need one extra dummy byte to detect the end of the stream.
            inflater.setInput(input + 0)
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(512)
            while (!inflater.finished()) {
                val n = try {
                    inflater.inflate(buffer)
                } catch (e: DataFormatException) {
                    fail(ProfileShareException.Reason.CORRUPTED, "The profile code is damaged.")
                }
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary()))
                    fail(ProfileShareException.Reason.CORRUPTED, "The profile code is incomplete.")
                out.write(buffer, 0, n)
                if (out.size() > MAX_JSON_BYTES)
                    fail(ProfileShareException.Reason.TOO_LARGE, "This profile is too large.")
            }
            return out.toByteArray()
        } finally {
            inflater.end()
        }
    }

    private fun notEqify(): Nothing =
        fail(ProfileShareException.Reason.NOT_EQIFY, "This is not an EQify profile.")

    private fun invalid(message: String): Nothing =
        fail(ProfileShareException.Reason.INVALID_DATA, message)

    private fun fail(reason: ProfileShareException.Reason, message: String): Nothing =
        throw ProfileShareException(reason, message)
}

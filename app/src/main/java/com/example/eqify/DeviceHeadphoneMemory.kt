package com.example.eqify

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * What the user chose for a specific output device, remembered so it is applied
 * automatically next time (FEATURE_HEADPHONE_DETECTION.md). Pure Kotlin.
 */
sealed interface DeviceChoice {
    data class Dataset(val name: String) : DeviceChoice
    data class Custom(val profileId: String) : DeviceChoice
    /** "Not headphones" (car, speaker…): use no headphone correction on this device. */
    data object NotHeadphones : DeviceChoice
}

object DeviceHeadphoneMemory {
    private const val PREFIX = "v1:"

    /** Stable key for a device: "bluetooth:wh1000xm5", "usb:...", or "wired". */
    fun key(kind: AudioOutputKind, productName: String?): String = when (kind) {
        AudioOutputKind.WIRED -> "wired"
        else -> {
            val n = HeadphoneMatcher.normalise(productName.orEmpty())
            if (n.isEmpty()) kind.name.lowercase() else "${kind.name.lowercase()}:$n"
        }
    }

    fun encode(map: Map<String, DeviceChoice>): String = PREFIX + map.entries.joinToString("|") { (k, v) ->
        enc(k) + "=" + enc(
            when (v) {
                is DeviceChoice.Dataset -> "dataset:" + v.name
                is DeviceChoice.Custom -> "custom:" + v.profileId
                DeviceChoice.NotHeadphones -> "none"
            }
        )
    }

    /** Unknown or malformed entries are skipped, never thrown. */
    fun decode(raw: String?): Map<String, DeviceChoice> {
        if (raw.isNullOrBlank() || !raw.startsWith(PREFIX)) return emptyMap()
        val body = raw.removePrefix(PREFIX)
        if (body.isEmpty()) return emptyMap()
        return body.split("|").mapNotNull { pair ->
            val parts = pair.split("=", limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val key = dec(parts[0]) ?: return@mapNotNull null
            val value = dec(parts[1]) ?: return@mapNotNull null
            val choice = when {
                value == "none" -> DeviceChoice.NotHeadphones
                value.startsWith("dataset:") && value.length > 8 -> DeviceChoice.Dataset(value.removePrefix("dataset:"))
                value.startsWith("custom:") && value.length > 7 -> DeviceChoice.Custom(value.removePrefix("custom:"))
                else -> null
            } ?: return@mapNotNull null
            if (key.isBlank()) null else key to choice
        }.toMap()
    }

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8.name())
    private fun dec(s: String) = runCatching { URLDecoder.decode(s, Charsets.UTF_8.name()) }.getOrNull()
}

/** Which kind of audio output is current. Shared by the detector, EQ service and UI. */
enum class AudioOutputKind { BLUETOOTH, USB, WIRED, SPEAKER }

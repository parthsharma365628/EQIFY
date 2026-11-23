package com.example.eqify

import java.net.URLDecoder
import java.net.URLEncoder

/** What a device does for "Sound" (FEATURES_NEXT_PLAN.md §4). Null on the profile = same as default. */
sealed interface DeviceSound {
    data object FollowGenre : DeviceSound
    data class Preset(val name: String) : DeviceSound
}

/**
 * A device's own sound settings. Every setting is optional: null means "same as
 * default". Headphone correction is remembered separately ([DeviceChoice]).
 */
data class DeviceProfile(
    val label: String? = null,
    val kind: AudioOutputKind,
    val lastSeenMillis: Long = 0L,
    val sound: DeviceSound? = null,
    val bassDb: Float? = null,
    val genreStrength: Float? = null
) {
    val isCustomised: Boolean get() = sound != null || bassDb != null || genreStrength != null
}

/** The defaults every device inherits. */
data class GlobalSound(val autoGenre: Boolean, val bassDb: Float, val genreStrength: Float)

/** What actually applies on the current device, and where each value came from. */
data class EffectiveSound(
    val autoGenre: Boolean,
    /** A preset this device always uses, or null. */
    val devicePreset: String?,
    val bassDb: Float,
    val genreStrength: Float,
    val soundFromDevice: Boolean,
    val bassFromDevice: Boolean,
    val strengthFromDevice: Boolean
)

object DeviceProfiles {
    private const val PREFIX = "v1:"
    val BASS_STEPS = listOf(0f, 3f, 6f, 9f)

    /** Precedence: device setting, then the default. (A manual change this session is handled by the EQ paths.) */
    fun resolve(global: GlobalSound, profile: DeviceProfile?): EffectiveSound {
        val sound = profile?.sound
        val bass = profile?.bassDb?.let(::normaliseBass)
        val strength = profile?.genreStrength?.let(GenreStrength::normalise)
        return EffectiveSound(
            autoGenre = when (sound) {
                null -> global.autoGenre
                DeviceSound.FollowGenre -> true
                is DeviceSound.Preset -> false
            },
            devicePreset = (sound as? DeviceSound.Preset)?.name,
            bassDb = bass ?: normaliseBass(global.bassDb),
            genreStrength = strength ?: GenreStrength.normalise(global.genreStrength),
            soundFromDevice = sound != null,
            bassFromDevice = bass != null,
            strengthFromDevice = strength != null
        )
    }

    fun normaliseBass(value: Float): Float =
        if (!value.isFinite()) 0f else BASS_STEPS.minByOrNull { kotlin.math.abs(it - value) } ?: 0f

    /** Default name for a device that has not been renamed. */
    fun defaultLabel(key: String, kind: AudioOutputKind): String = when (kind) {
        AudioOutputKind.SPEAKER -> "Phone speaker"
        AudioOutputKind.WIRED -> "Wired headphones"
        AudioOutputKind.USB -> if (key.contains(':')) key.substringAfter(':').prettify() else "USB headphones"
        AudioOutputKind.BLUETOOTH -> if (key.contains(':')) key.substringAfter(':').prettify() else "Bluetooth device"
    }

    /** Short line for the device list, e.g. "Rock preset, bass +3". Never empty. */
    fun summary(profile: DeviceProfile?, choice: DeviceChoice?, presetLabel: (String) -> String = { it }): String {
        val parts = buildList {
            when (val s = profile?.sound) {
                DeviceSound.FollowGenre -> add("follows genre")
                is DeviceSound.Preset -> add("${presetLabel(s.name)} preset")
                null -> Unit
            }
            profile?.bassDb?.let { add(if (it <= 0f) "bass off" else "bass +${it.toInt()}") }
            profile?.genreStrength?.let { add("strength ${Math.round(GenreStrength.normalise(it) * 100)}%") }
            if (choice == DeviceChoice.NotHeadphones) add("no correction")
        }
        return if (parts.isEmpty()) "Same as default" else parts.joinToString(", ").replaceFirstChar { it.uppercase() }
    }

    // ── Preset follow-through ─────────────────────────────────────────

    fun renamePreset(map: Map<String, DeviceProfile>, from: String, to: String): Map<String, DeviceProfile> =
        map.mapValues { (_, p) ->
            if ((p.sound as? DeviceSound.Preset)?.name == from) p.copy(sound = DeviceSound.Preset(to)) else p
        }

    /** A deleted preset falls back to "same as default". */
    fun removePreset(map: Map<String, DeviceProfile>, name: String): Map<String, DeviceProfile> =
        map.mapValues { (_, p) -> if ((p.sound as? DeviceSound.Preset)?.name == name) p.copy(sound = null) else p }

    // ── Storage ───────────────────────────────────────────────────────
    // v1:key=label,kind,lastSeen,sound,bass,strength|… ; every field URL-encoded, "" = null.

    fun encode(map: Map<String, DeviceProfile>): String = PREFIX + map.entries
        .filter { it.key.isNotBlank() }
        .joinToString("|") { (key, p) ->
            enc(key) + "=" + listOf(
                p.label.orEmpty(),
                p.kind.name,
                p.lastSeenMillis.toString(),
                when (val s = p.sound) {
                    null -> ""
                    DeviceSound.FollowGenre -> "genre"
                    is DeviceSound.Preset -> "preset:" + s.name
                },
                p.bassDb?.toString().orEmpty(),
                p.genreStrength?.toString().orEmpty()
            ).joinToString(",") { enc(it) }
        }

    /** Unknown or malformed entries are skipped, never thrown. */
    fun decode(raw: String?): Map<String, DeviceProfile> {
        if (raw.isNullOrBlank() || !raw.startsWith(PREFIX)) return emptyMap()
        val body = raw.removePrefix(PREFIX)
        if (body.isEmpty()) return emptyMap()
        return body.split("|").mapNotNull { entry ->
            val kv = entry.split("=", limit = 2)
            if (kv.size != 2) return@mapNotNull null
            val key = dec(kv[0])?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val f = kv[1].split(",").map { dec(it) ?: return@mapNotNull null }
            if (f.size != 6) return@mapNotNull null
            val kind = runCatching { AudioOutputKind.valueOf(f[1]) }.getOrNull() ?: return@mapNotNull null
            val sound = when {
                f[3].isEmpty() -> null
                f[3] == "genre" -> DeviceSound.FollowGenre
                f[3].startsWith("preset:") && f[3].length > 7 -> DeviceSound.Preset(f[3].removePrefix("preset:"))
                else -> null
            }
            key to DeviceProfile(
                label = f[0].takeIf { it.isNotBlank() },
                kind = kind,
                lastSeenMillis = f[2].toLongOrNull()?.coerceAtLeast(0L) ?: 0L,
                sound = sound,
                bassDb = f[4].toFloatOrNull()?.takeIf { it.isFinite() }?.let(::normaliseBass),
                genreStrength = f[5].toFloatOrNull()?.takeIf { it.isFinite() }?.let(GenreStrength::normalise)
            )
        }.toMap()
    }

    private fun String.prettify(): String =
        split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8.name())
    private fun dec(s: String) = runCatching { URLDecoder.decode(s, Charsets.UTF_8.name()) }.getOrNull()
}

package com.example.eqify

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** A semantic version such as 0.2.0-beta.1. Ordering follows semver precedence. */
data class AppVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: List<String> = emptyList()
) : Comparable<AppVersion> {

    override fun compareTo(other: AppVersion): Int {
        compareValues(major, other.major).takeIf { it != 0 }?.let { return it }
        compareValues(minor, other.minor).takeIf { it != 0 }?.let { return it }
        compareValues(patch, other.patch).takeIf { it != 0 }?.let { return it }
        // A release is newer than any of its pre-releases.
        if (preRelease.isEmpty() && other.preRelease.isEmpty()) return 0
        if (preRelease.isEmpty()) return 1
        if (other.preRelease.isEmpty()) return -1
        for (i in 0 until minOf(preRelease.size, other.preRelease.size)) {
            val a = preRelease[i]
            val b = other.preRelease[i]
            val an = a.toIntOrNull()
            val bn = b.toIntOrNull()
            val c = when {
                an != null && bn != null -> an.compareTo(bn)
                an != null -> -1 // numeric identifiers sort before text
                bn != null -> 1
                else -> a.compareTo(b)
            }
            if (c != 0) return c
        }
        return preRelease.size.compareTo(other.preRelease.size)
    }

    override fun toString(): String =
        "$major.$minor.$patch" + if (preRelease.isEmpty()) "" else "-" + preRelease.joinToString(".")

    companion object {
        private val PATTERN = Regex("""^[vV]?(\d{1,6})\.(\d{1,6})(?:\.(\d{1,6}))?(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$""")

        /** Parses "0.2.0-beta", "v1.0", "1.2.3-rc.1"; null for anything else. */
        fun parse(raw: String?): AppVersion? {
            val m = PATTERN.matchEntire(raw?.trim().orEmpty()) ?: return null
            val pre = m.groupValues[4].takeIf { it.isNotEmpty() }?.split('.') ?: emptyList()
            if (pre.any { it.isEmpty() }) return null
            return AppVersion(
                m.groupValues[1].toInt(), m.groupValues[2].toInt(),
                m.groupValues[3].ifEmpty { "0" }.toInt(), pre
            )
        }
    }
}

/** A published release, from the public eqify-releases repository. */
data class ReleaseInfo(val tag: String, val version: AppVersion, val url: String)

/** Pure rules for the update check (FEATURES_NEXT_PLAN.md §1). */
object UpdateRules {
    const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

    fun isDue(nowMillis: Long, lastCheckMillis: Long): Boolean =
        lastCheckMillis <= 0L || nowMillis - lastCheckMillis >= CHECK_INTERVAL_MS || nowMillis < lastCheckMillis

    /** Newest non-draft release with a parseable tag (pre-releases included). */
    fun newest(releases: JsonArray): ReleaseInfo? = releases.mapNotNull { element ->
        val obj = runCatching { element.asJsonObject }.getOrNull() ?: return@mapNotNull null
        if (obj.get("draft")?.takeIf { it.isJsonPrimitive }?.asBoolean == true) return@mapNotNull null
        val tag = obj.get("tag_name")?.takeIf { it.isJsonPrimitive }?.asString ?: return@mapNotNull null
        val url = obj.get("html_url")?.takeIf { it.isJsonPrimitive }?.asString
            ?.takeIf { it.startsWith("https://github.com/") } ?: return@mapNotNull null
        val version = AppVersion.parse(tag) ?: return@mapNotNull null
        ReleaseInfo(tag, version, url)
    }.maxByOrNull { it.version }

    fun isNewer(release: ReleaseInfo?, installed: AppVersion?): Boolean =
        release != null && installed != null && release.version > installed
}

/**
 * Checks GitHub for a newer EQify at most once a day. Failures are silent;
 * only a manual check reports them.
 */
object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private const val RELEASES_API =
        "https://api.github.com/repos/parthsharma365628/eqify-releases/releases?per_page=5"
    const val RELEASES_PAGE = "https://github.com/parthsharma365628/eqify-releases/releases"

    val installed: AppVersion? = AppVersion.parse(BuildConfig.VERSION_NAME)

    sealed interface Status {
        data object Idle : Status
        data object Checking : Status
        data object UpToDate : Status
        data class Available(val release: ReleaseInfo) : Status
        data object Failed : Status
    }

    private val _latest = MutableStateFlow<ReleaseInfo?>(null)
    /** The newest known release that is newer than this build, or null. */
    val latest: StateFlow<ReleaseInfo?> = _latest.asStateFlow()

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val lock = Mutex()
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /** Restores the last known release, so the banner survives restarts between checks. */
    suspend fun restore(repo: UserPreferencesRepository) {
        val stored = repo.latestKnownRelease.first()
        _latest.value = stored?.takeIf { UpdateRules.isNewer(it, installed) }
    }

    /** Automatic check: release builds only, when enabled and due. */
    suspend fun checkIfDue(repo: UserPreferencesRepository) {
        if (BuildConfig.DEBUG || !repo.autoUpdateCheck.first()) return
        if (!UpdateRules.isDue(System.currentTimeMillis(), repo.lastUpdateCheck.first())) return
        check(repo)
    }

    /** Manual check from Settings; always runs and reports the result. */
    suspend fun checkNow(repo: UserPreferencesRepository) { check(repo) }

    private suspend fun check(repo: UserPreferencesRepository) = lock.withLock {
        _status.value = Status.Checking
        val release = try {
            fetchNewest()
        } catch (e: kotlinx.coroutines.CancellationException) {
            _status.value = Status.Idle
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed: ${e.message}")
            _status.value = Status.Failed
            return@withLock
        }
        repo.recordUpdateCheck(System.currentTimeMillis(), release)
        val newer = release?.takeIf { UpdateRules.isNewer(it, installed) }
        _latest.value = newer
        _status.value = if (newer != null) Status.Available(newer) else Status.UpToDate
    }

    private suspend fun fetchNewest(): ReleaseInfo? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(RELEASES_API)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "EQify-Android")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code()}")
            val body = response.body()?.string() ?: error("Empty response")
            if (body.length > 512_000) error("Response too large")
            UpdateRules.newest(JsonParser().parse(body).asJsonArray)
        }
    }
}

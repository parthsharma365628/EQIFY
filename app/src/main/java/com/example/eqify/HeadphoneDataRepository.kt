package com.example.eqify

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStreamWriter

class HeadphoneDataException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/** Static AutoEQ catalog and profile access backed by GitHub Pages. */
object HeadphoneDataRepository {
    private const val TAG = "HeadphoneData"
    private const val SCHEMA_VERSION = 1
    private const val INDEX_CACHE_FILE = "headphone-index.json"
    private val expectedFrequencies = EqProfileManager.BAND_CENTERS_HZ.toList()
    private val gson = Gson()
    private val indexMutex = Mutex()

    @Volatile
    private var memoryIndex: StaticHeadphoneIndex? = null

    suspend fun search(
        context: Context,
        query: String,
        forceRefresh: Boolean = false
    ): List<StaticHeadphoneSummary> {
        val index = getIndex(context.applicationContext, forceRefresh)
        val term = query.trim()
        return index.headphones.asSequence()
            .filter { term.isBlank() || it.name.contains(term, ignoreCase = true) }
            .take(50)
            .toList()
    }

    suspend fun getProfileGains(context: Context, headphoneName: String): FloatArray {
        val index = getIndex(context.applicationContext)
        val summary = index.headphones.firstOrNull {
            it.name.equals(headphoneName, ignoreCase = true)
        } ?: throw HeadphoneDataException(
            "No gain profile was found for $headphoneName. Flat correction is active."
        )

        if (!summary.profilePath.matches(Regex("^profiles/[0-9a-f]{2}/[0-9a-f]{64}\\.json$"))) {
            throw HeadphoneDataException("Headphone profile address is invalid.")
        }

        val profile = try {
            HeadphoneDataClient.apiService.getProfile(summary.profilePath)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Profile download failed for '$headphoneName'", e)
            throw HeadphoneDataException(
                "Gain values could not be downloaded. Check your internet connection and try again.",
                e
            )
        }

        if (
            profile.schemaVersion != SCHEMA_VERSION ||
            profile.datasetVersion != index.datasetVersion ||
            profile.frequenciesHz != expectedFrequencies ||
            !profile.name.equals(summary.name, ignoreCase = true) ||
            profile.gains.size != expectedFrequencies.size ||
            profile.gains.any { !it.isFinite() }
        ) {
            Log.w(TAG, "Invalid profile returned for '$headphoneName'")
            throw HeadphoneDataException("Downloaded gain values were invalid. Flat correction is active.")
        }

        return profile.gains.toFloatArray()
    }

    private suspend fun getIndex(
        context: Context,
        forceRefresh: Boolean = false
    ): StaticHeadphoneIndex {
        if (!forceRefresh) memoryIndex?.let { return it }

        return indexMutex.withLock {
            if (!forceRefresh) {
                memoryIndex?.let { return@withLock it }
                loadCachedIndex(context)?.let {
                    memoryIndex = it
                    return@withLock it
                }
            }

            val downloaded = try {
                HeadphoneDataClient.apiService.getIndex()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Catalog download failed", e)
                if (forceRefresh) {
                    memoryIndex?.let { return@withLock it }
                    loadCachedIndex(context)?.let { return@withLock it }
                }
                throw HeadphoneDataException(
                    "Headphone database could not be downloaded. Check your internet connection and try again.",
                    e
                )
            }

            if (!isValidIndex(downloaded)) {
                throw HeadphoneDataException("Downloaded headphone database was invalid.")
            }

            saveIndex(context, downloaded)
            memoryIndex = downloaded
            downloaded
        }
    }

    private suspend fun loadCachedIndex(context: Context): StaticHeadphoneIndex? =
        withContext(Dispatchers.IO) {
            val file = File(context.filesDir, INDEX_CACHE_FILE)
            if (!file.isFile) return@withContext null
            runCatching {
                gson.fromJson(file.readText(), StaticHeadphoneIndex::class.java)
            }.onFailure {
                Log.w(TAG, "Cached catalog could not be read", it)
            }.getOrNull()?.takeIf(::isValidIndex)
        }

    private suspend fun saveIndex(context: Context, index: StaticHeadphoneIndex) =
        withContext(Dispatchers.IO) {
            val atomicFile = AtomicFile(File(context.filesDir, INDEX_CACHE_FILE))
            var output = atomicFile.startWrite()
            try {
                OutputStreamWriter(output, Charsets.UTF_8).apply {
                    gson.toJson(index, this)
                    flush()
                }
                atomicFile.finishWrite(output)
            } catch (e: Exception) {
                atomicFile.failWrite(output)
                throw e
            }
        }

    private fun isValidIndex(index: StaticHeadphoneIndex): Boolean =
        index.schemaVersion == SCHEMA_VERSION &&
            index.datasetVersion.isNotBlank() &&
            index.frequenciesHz == expectedFrequencies &&
            index.checksum.matches(Regex("^[0-9a-f]{64}$")) &&
            index.headphones.isNotEmpty() &&
            index.headphones.all { it.name.isNotBlank() && it.profilePath.isNotBlank() }
}

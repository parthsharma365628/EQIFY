package com.example.eqify

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

/**
 * Persists last-known AutoEQ 8-band curves per headphone name so EQ still works offline
 * after a successful fetch once.
 */
object HeadphoneEqDiskCache {

    private const val TAG = "HeadphoneEqDiskCache"
    private const val FILE_NAME = "headphone_eq_cache.json"

    private fun file(ctx: Context) = File(ctx.filesDir, FILE_NAME)

    private fun loadAll(ctx: Context): JSONObject {
        val f = file(ctx)
        if (!f.exists()) return JSONObject()
        return try {
            JSONObject(f.readText())
        } catch (e: Exception) {
            Log.w(TAG, "Corrupt cache, resetting: ${e.message}")
            JSONObject()
        }
    }

    private fun writeAll(ctx: Context, root: JSONObject) {
        try {
            file(ctx).writeText(root.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write cache: ${e.message}")
        }
    }

    private fun keyFor(name: String) =
        URLEncoder.encode(name.trim(), Charsets.UTF_8.name())

    fun load(ctx: Context, headphoneName: String): FloatArray? {
        val key = keyFor(headphoneName)
        val root = loadAll(ctx)
        if (!root.has(key)) return null
        return try {
            val arr = root.getJSONArray(key)
            if (arr.length() != 8) return null
            FloatArray(8) { i -> arr.getDouble(i).toFloat() }
        } catch (e: Exception) {
            null
        }
    }

    fun save(ctx: Context, headphoneName: String, gains: FloatArray) {
        if (gains.size != 8) return
        val root = loadAll(ctx)
        val arr = JSONArray()
        for (g in gains) arr.put(g.toDouble())
        root.put(keyFor(headphoneName), arr)
        writeAll(ctx, root)
    }

    fun entryCount(ctx: Context): Int = loadAll(ctx).length()

    /** Removes only downloaded AutoEQ correction curves. */
    fun clear(ctx: Context): Int {
        val count = entryCount(ctx)
        val cacheFile = file(ctx)
        if (cacheFile.exists() && !cacheFile.delete()) {
            Log.w(TAG, "Failed to delete correction cache")
            return -1
        }
        return count
    }
}

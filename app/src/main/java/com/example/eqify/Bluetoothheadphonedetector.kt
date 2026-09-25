package com.example.eqify

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Detects Bluetooth and wired headphone connections.
 *
 * Bluetooth: auto-selects matching AutoEQ model if found.
 * Wired: shows a confirmation banner since wired headphone names
 *        are always generic ("Headset", "3.5mm") — user confirms.
 *
 * Both detection paths are gated behind the headphoneAutoDetect
 * setting in UserPreferencesRepository — if the user turns it off,
 * no prompts and no auto-selection.
 */
object BluetoothHeadphoneDetector {

    private const val TAG = "BluetoothHeadphoneDetector"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val matchCache = mutableMapOf<String, String?>()

    // ── Wired headphone banner state ──────────────────────────────────
    // Observed by HomeScreen to show the confirmation banner.
    private val _wiredConnected = MutableStateFlow(false)
    val wiredConnected = _wiredConnected.asStateFlow()

    // ── Broadcast receiver ────────────────────────────────────────────

    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            scope.launch {
                // Gate everything behind the setting
                val repo    = (context.applicationContext as EqifyApplication).repository
                val enabled = repo.headphoneAutoDetect.first()
                if (!enabled) return@launch

                when (intent.action) {

                    BluetoothDevice.ACTION_ACL_CONNECTED -> {
                        val device: BluetoothDevice? =
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                intent.getParcelableExtra(
                                    BluetoothDevice.EXTRA_DEVICE,
                                    BluetoothDevice::class.java
                                )
                            } else {
                                @Suppress("DEPRECATION")
                                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                            }

                        val deviceName = try { device?.name } catch (e: SecurityException) {
                            Log.w(TAG, "BLUETOOTH_CONNECT not granted: ${e.message}")
                            null
                        }

                        if (deviceName != null) {
                            Log.d(TAG, "Bluetooth connected: $deviceName")
                            tryAutoSelect(context, deviceName)
                        }
                    }

                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                        Log.d(TAG, "Bluetooth disconnected")
                    }

                    AudioManager.ACTION_HEADSET_PLUG -> {
                        val state = intent.getIntExtra("state", -1)
                        if (state == 1) {
                            // Wired headphones plugged in — show confirmation banner
                            // (we can't reliably get the model name from wired)
                            Log.d(TAG, "Wired headphones plugged in")
                            _wiredConnected.value = true
                        } else if (state == 0) {
                            // Unplugged — dismiss banner if still showing
                            _wiredConnected.value = false
                        }
                    }
                }
            }
        }
    }

    fun getIntentFilter() = IntentFilter().apply {
        addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
        addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        addAction(AudioManager.ACTION_HEADSET_PLUG)
    }

    // ── Dismiss wired banner (called when user taps Change or dismisses) ──

    fun dismissWiredBanner() {
        _wiredConnected.value = false
    }

    // ── Auto-selection logic ──────────────────────────────────────────

    private suspend fun tryAutoSelect(context: Context, deviceName: String) {
        val cacheKey = normalise(deviceName)
        val match = if (matchCache.containsKey(cacheKey)) {
            matchCache[cacheKey]
        } else {
            val searchName = deviceName.replace(Regex("(?i)^LE[_\\s-]*"), "").trim()
            val candidates = fetchHeadphoneNames(context, searchName)
            val resolved = findBestMatch(searchName, candidates)
            matchCache[cacheKey] = resolved
            resolved
        }

        if (match != null) {
            Log.d(TAG, "Auto-selected: '$deviceName' → '$match'")
            NowPlayingState.updateSelectedHeadphone(match)
            val repo = (context.applicationContext as EqifyApplication).repository
            repo.setSelectedHeadphone(match)
        } else {
            Log.d(TAG, "No AutoEQ match for '$deviceName' — keeping current selection")
        }
    }

    internal fun findBestMatch(deviceName: String, candidates: List<String>): String? {
        candidates.find { it == deviceName }?.let { return it }
        val lower = deviceName.lowercase()
        candidates.find { it.lowercase() == lower }?.let { return it }
        candidates.find { candidate ->
            val cl = candidate.lowercase()
            lower.contains(cl) || cl.contains(lower)
        }?.let { return it }
        val normalised = normalise(deviceName)
        candidates.find { normalise(it) == normalised }?.let { return it }
        candidates.find { candidate ->
            val cn = normalise(candidate)
            normalised.contains(cn) || cn.contains(normalised)
        }?.let { return it }
        return null
    }

    private fun normalise(name: String): String =
        name.lowercase().replace(Regex("[\\s\\-_]"), "")

    private suspend fun fetchHeadphoneNames(context: Context, query: String): List<String> {
        return try {
            HeadphoneDataRepository.search(context, query).map { it.name }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch headphone list: ${e.message}")
            emptyList()
        }
    }

}

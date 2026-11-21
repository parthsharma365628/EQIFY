package com.example.eqify

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * Hands profile text from an incoming Intent (eqify:// link or "Share to EQify")
 * to the EQ screen, which only ever opens the import *preview*. Nothing is saved
 * or applied without the user confirming there.
 */
object PendingProfileImport {
    private val _text = MutableStateFlow<String?>(null)
    val text = _text.asStateFlow()

    /** Returns true when the intent carried profile text worth showing. */
    fun offer(intent: Intent?): Boolean {
        val extracted = extract(intent) ?: return false
        _text.value = extracted
        return true
    }

    /** Takes the pending text once; later callers get null. */
    fun consume(): String? = _text.getAndUpdate { null }

    private fun extract(intent: Intent?): String? {
        intent ?: return null
        val text = when (intent.action) {
            Intent.ACTION_VIEW -> {
                val data = intent.data ?: return null
                if (data.scheme != "eqify" || data.host != "import") return null
                runCatching { data.getQueryParameter("p") }.getOrNull() ?: intent.dataString
            }
            Intent.ACTION_SEND ->
                if (intent.type?.startsWith("text/") == true)
                    intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                else null
            else -> null
        }
        // Keep one character over the codec's cap so it still reports "too large".
        return text?.takeIf { it.isNotBlank() }?.take(EqProfileCodec.MAX_INPUT_CHARS + 1)
    }
}

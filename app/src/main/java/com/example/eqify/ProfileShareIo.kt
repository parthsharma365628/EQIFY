package com.example.eqify

import android.content.Context
import android.content.Intent
import android.net.Uri

/** Android-side helpers shared by every screen that exports a profile. */
object ProfileShareIo {

    /** Writes the human-readable JSON form. Call off the main thread. */
    fun writeJsonFile(context: Context, uri: Uri, profile: SharedEqProfile) {
        val json = EqProfileCodec.encodeJson(profile)
        context.contentResolver.openOutputStream(uri, "wt")?.use {
            it.write(json.toByteArray(Charsets.UTF_8))
        } ?: error("Could not open the chosen file.")
    }

    /** A chooser for sending the code as text (chat apps, email, notes). */
    fun shareTextChooser(name: String, code: String): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "EQify profile: $name")
            putExtra(
                Intent.EXTRA_TEXT,
                "EQify profile \"$name\". To use it, open EQify → Equalizer → " +
                    "presets → Import shared profile, and paste this code:\n\n$code"
            )
        }
        return Intent.createChooser(send, "Share EQ profile")
    }
}

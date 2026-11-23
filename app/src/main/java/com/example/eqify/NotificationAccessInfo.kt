package com.example.eqify

import android.os.Build

/**
 * Shared wording for every Notification Access prompt. Android's own dialog lists
 * everything the permission could allow (read, dismiss, reply); this says what
 * EQify actually does (see MediaListenerService.SUPPORTED_PACKAGES).
 */
object NotificationAccessInfo {
    const val WHAT_EQIFY_DOES =
        "Android describes this access broadly (read, dismiss and reply). EQify only reads the song " +
            "title and artist from Spotify, YouTube Music, YouTube and Apple Music. It never reads, " +
            "dismisses or replies to any other notification."

    /** Android 13+ blocks this switch for apps installed outside a store until the user allows it. */
    val restrictedSettingsHint: String?
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            "If the switch is greyed out, open Settings > Apps > EQify, tap the ⋮ menu, choose " +
                "\"Allow restricted settings\", then try again."
        else null

    /** Full explanation for a dialog, with the hint where it applies. */
    fun dialogText(lead: String): String =
        listOfNotNull(lead, WHAT_EQIFY_DOES, restrictedSettingsHint).joinToString("\n\n")
}

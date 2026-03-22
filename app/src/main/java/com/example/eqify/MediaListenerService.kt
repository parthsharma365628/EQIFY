package com.example.eqify

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Listens to status-bar notifications from streaming apps (Spotify, YouTube Music)
 * and extracts track/artist metadata from the notification extras.
 *
 * Changes in this version:
 *  1. [onNotificationRemoved] now calls [NowPlayingState.clearSong] so the UI
 *     and EQ service properly reset when music stops.
 *  2. Artist string de-duplication: Spotify sometimes posts the same
 *     notification twice when pausing/resuming — we guard against redundant
 *     API calls by checking if the track actually changed.
 */
class MediaListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "MediaListenerService"

        // Package names we care about
        private val SUPPORTED_PACKAGES = setOf(
            "com.spotify.music",
            "com.google.android.apps.youtube.music",
            "com.google.android.youtube",
            "com.apple.android.music"
        )
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var isFeatureEnabled = true

    // Track the last posted song to avoid redundant API calls on duplicate notifications
    private var lastTrack  = ""
    private var lastArtist = ""

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        // Observe the feature toggle so we can stop processing without
        // destroying the service (destroying requires a permission re-grant)
        serviceScope.launch {
            NowPlayingState.mediaListenerEnabled.collectLatest { enabled ->
                isFeatureEnabled = enabled
                if (!enabled) {
                    // Clear state when user disables the listener
                    NowPlayingState.clearSong()
                    lastTrack  = ""
                    lastArtist = ""
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Notification events
    // ------------------------------------------------------------------

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!isFeatureEnabled) return
        if (sbn.packageName !in SUPPORTED_PACKAGES) return

        val extras     = sbn.notification.extras
        val trackName  = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        val artistName = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()  ?: return

        if (trackName.isBlank() || artistName.isBlank()) return

        // De-duplicate: ignore if this is exactly the same song we already reported
        if (trackName == lastTrack && artistName == lastArtist) return

        lastTrack  = trackName
        lastArtist = artistName

        Log.d(TAG, "Now playing: '$trackName' by '$artistName' (${sbn.packageName})")
        NowPlayingState.updateSong(trackName, artistName)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (!isFeatureEnabled) return
        if (sbn.packageName !in SUPPORTED_PACKAGES) return

        // When the media notification disappears (music stopped / paused and dismissed),
        // reset the Now Playing state so the UI and EQ service go back to idle.
        // We only clear if the removed notification was for the track we're tracking.
        val extras    = sbn.notification.extras
        val trackName = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()

        if (trackName == lastTrack) {
            Log.d(TAG, "Music stopped — clearing Now Playing state")
            NowPlayingState.clearSong()
            lastTrack  = ""
            lastArtist = ""
        }
    }
}
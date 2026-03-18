package com.example.eqify

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class MediaListenerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName

        // Listen specifically to Spotify and YouTube Music
        if (packageName == "com.spotify.music" || packageName == "com.google.android.apps.youtube.music") {

            val extras = sbn.notification.extras
            val trackName = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            val artistName = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

            // Make sure it's actually a song and not just a random notification
            if (!trackName.isNullOrBlank() && !artistName.isNullOrBlank()) {
                // Send the data to our bridge!
                NowPlayingState.updateSong(trackName, artistName)

                // TODO: Next we will send this to your HomeViewModel!
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // We can handle what happens when the music stops later
    }
}
package com.example.eqify

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object NowPlayingState {
    private val _track = MutableStateFlow("Waiting for music...")
    val track = _track.asStateFlow()

    private val _artist = MutableStateFlow("")
    val artist = _artist.asStateFlow()

    fun updateSong(newTrack: String, newArtist: String) {
        _track.value = newTrack
        _artist.value = newArtist
    }
}
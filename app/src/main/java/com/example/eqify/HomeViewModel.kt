package com.example.eqify

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
// Keep your existing imports
import com.example.eqify.GenreRequest
import com.example.eqify.RetrofitClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HomeViewModel : ViewModel() {

    // Holds the currently detected genre
    private val _currentGenre = MutableStateFlow("Detecting...")
    val currentGenre: StateFlow<String> = _currentGenre.asStateFlow()

    // 1. Point these directly to our live bridge!
    val currentTrack = NowPlayingState.track
    val currentArtist = NowPlayingState.artist

    init {
        // 2. Listen for changes from the Service continuously
        viewModelScope.launch {
            // Whenever the track changes in NowPlayingState, this block runs automatically
            NowPlayingState.track.collect { newTrack ->
                // Don't fetch the genre if it's just the placeholder text
                if (newTrack != "Waiting for music..." && newTrack.isNotBlank()) {
                    _currentGenre.value = "Detecting..." // Reset UI while fetching

                    // Trigger the API call with the LIVE data
                    fetchGenreForCurrentTrack(newTrack, NowPlayingState.artist.value)
                }
            }
        }
    }

    // 3. Updated this function to accept the live track/artist as arguments
    private fun fetchGenreForCurrentTrack(track: String, artist: String) {
        viewModelScope.launch {
            try {
                // Call our Node.js backend!
                val request = GenreRequest(track, artist)
                val response = RetrofitClient.apiService.resolveGenre(request)

                // Update the UI with the result
                _currentGenre.value = response.genre
            } catch (e: Exception) {
                _currentGenre.value = "Unknown"
                e.printStackTrace()
            }
        }
    }
}
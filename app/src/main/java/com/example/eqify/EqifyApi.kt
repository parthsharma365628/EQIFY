package com.example.eqify

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

// ── Request / Response models ──────────────────────────────────────

data class GenreRequest(val track: String, val artist: String)
data class GenreResponse(val genre: String, val source: String)
data class HeadphoneResponse(val name: String, val type: String)

// One band from /api/eq/:headphoneName
data class EqBandResponse(val frequencyHz: Int, val gainDb: Float)

// Full response from /api/eq/:headphoneName
data class HeadphoneEqResponse(
    val headphone: String,
    val bands: List<EqBandResponse>
)

// ── API interface ──────────────────────────────────────────────────

interface EqifyApiService {

    @POST("api/v1/genre")
    suspend fun resolveGenre(@Body request: GenreRequest): GenreResponse

    @GET("api/headphones")
    suspend fun getHeadphones(@Query("search") query: String): List<HeadphoneResponse>

    /**
     * Fetches the real 8-band AutoEQ correction curve for [headphoneName].
     * Called by [EqProcessingService] when the selected headphone changes.
     * Returns gains in dB, clamped to ±12, aligned to the 8-band layout:
     * 60Hz, 170Hz, 310Hz, 600Hz, 1kHz, 3kHz, 6kHz, 12kHz.
     */
    @GET("api/eq/{headphoneName}")
    suspend fun getHeadphoneEq(
        @Path("headphoneName", encoded = false) headphoneName: String
    ): HeadphoneEqResponse
}

// ── Retrofit client ────────────────────────────────────────────────

object RetrofitClient {

    private fun normalizeBaseUrl(raw: String): String =
        raw.trim().let { if (it.endsWith("/")) it else "$it/" }

    val apiService: EqifyApiService by lazy {
        Retrofit.Builder()
            .baseUrl(normalizeBaseUrl(BuildConfig.BASE_URL))
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(EqifyApiService::class.java)
    }
}
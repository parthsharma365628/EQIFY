package com.example.eqify

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

// ── Request / Response models ──────────────────────────────────────

data class GenreRequest(val track: String, val artist: String)
data class GenreResponse(val genre: String, val source: String)
data class HeadphoneResponse(val name: String, val type: String)
data class EqBandResponse(val frequencyHz: Int, val gainDb: Float)
data class HeadphoneEqResponse(val headphone: String, val bands: List<EqBandResponse>)

// ── API interface ──────────────────────────────────────────────────

interface EqifyApiService {

    @POST("api/v1/genre")
    suspend fun resolveGenre(@Body request: GenreRequest): GenreResponse

    @GET("api/headphones")
    suspend fun getHeadphones(@Query("search") query: String): List<HeadphoneResponse>

    @GET("api/eq/{headphoneName}")
    suspend fun getHeadphoneEq(
        @Path("headphoneName", encoded = false) headphoneName: String
    ): HeadphoneEqResponse
}

data class LastFmTag(val name: String)
data class LastFmTags(val tag: List<LastFmTag> = emptyList())
data class LastFmArtist(val tags: LastFmTags? = null)
data class LastFmResponse(val artist: LastFmArtist? = null)
data class ItunesTrack(
    val kind: String? = null,
    val trackName: String? = null,
    val artistName: String? = null,
    val primaryGenreName: String? = null
)
data class ItunesSearchResponse(val results: List<ItunesTrack> = emptyList())

interface LastFmApiService {
    @GET("2.0/")
    suspend fun getArtistInfo(
        @Query("method") method: String,
        @Query("artist") artist: String,
        @Query("api_key") apiKey: String,
        @Query("format") format: String,
        @Query("autocorrect") autocorrect: Int
    ): LastFmResponse
}

interface ItunesApiService {
    @GET("search")
    suspend fun searchSongs(
        @Query("term") term: String,
        @Query("country") country: String = "US",
        @Query("media") media: String = "music",
        @Query("entity") entity: String = "song",
        @Query("limit") limit: Int = 5
    ): ItunesSearchResponse
}

// ── Retrofit client ────────────────────────────────────────────────
//
// BASE_URL:
//   10.0.2.2:3000  = Android EMULATOR connecting to localhost on your PC.
//   If you are on a REAL PHONE on the same Wi-Fi as your PC,
//   change this to your PC's local IP e.g. "http://192.168.1.5:3000/"
//   To find your PC's IP: open CMD and run "ipconfig", look for IPv4 Address.
//
//   When you deploy the backend to Railway/Render, replace with your server URL.

object RetrofitClient {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(4, TimeUnit.SECONDS)
        .build()

    val apiService: EqifyApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BuildConfig.BASE_URL)
            .client(httpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(EqifyApiService::class.java)
    }
}

object LastFmClient {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(4, TimeUnit.SECONDS)
        .build()

    val apiService: LastFmApiService by lazy {
        Retrofit.Builder()
            .baseUrl("https://ws.audioscrobbler.com/")
            .client(httpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(LastFmApiService::class.java)
    }
}

object ItunesClient {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(4, TimeUnit.SECONDS)
        .build()

    val apiService: ItunesApiService by lazy {
        Retrofit.Builder()
            .baseUrl("https://itunes.apple.com/")
            .client(httpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ItunesApiService::class.java)
    }
}

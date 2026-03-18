package com.example.eqify

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST

// 1. Data classes to match the JSON from your Node.js backend
data class GenreRequest(val track: String, val artist: String)
data class GenreResponse(val genre: String, val source: String)

// 2. The API Interface
interface EqifyApiService {
    @POST("api/v1/genre")
    suspend fun resolveGenre(@Body request: GenreRequest): GenreResponse
}

// 3. The Retrofit Client
object RetrofitClient {
    // 10.0.2.2 is the magic IP that lets the Android Emulator talk to your computer's localhost
    private const val BASE_URL = "http://10.0.2.2:3000/"

    val apiService: EqifyApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(EqifyApiService::class.java)
    }
}
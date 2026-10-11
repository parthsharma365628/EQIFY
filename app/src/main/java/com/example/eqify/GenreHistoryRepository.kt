package com.example.eqify

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay
import java.util.Locale

data class TrackIdentity(val title: String, val artist: String) {
    companion object {
        fun of(title: String, artist: String) = TrackIdentity(normalize(title), normalize(artist))
        private fun normalize(value: String) = value.trim().replace(Regex("\\s+"), " ")
            .lowercase(Locale.ROOT)
    }
    val valid get() = title.isNotEmpty() && artist.isNotEmpty()
}

@Entity(
    tableName = "genre_observation",
    indices = [Index(value = ["normalizedTitle", "normalizedArtist"]), Index(value = ["observedAtEpochMillis"])]
)
data class GenreObservation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val observedAtEpochMillis: Long,
    val title: String,
    val artist: String,
    val normalizedTitle: String,
    val normalizedArtist: String,
    val loggedGenre: String,
    val genreOrigin: String
)

@Entity(tableName = "genre_correction", primaryKeys = ["normalizedTitle", "normalizedArtist"])
data class GenreCorrection(
    val normalizedTitle: String,
    val normalizedArtist: String,
    val title: String,
    val artist: String,
    val correctedGenre: String,
    val correctedAtEpochMillis: Long
)

@Dao
interface GenreHistoryDao {
    @Query("SELECT * FROM genre_correction WHERE normalizedTitle = :title AND normalizedArtist = :artist LIMIT 1")
    suspend fun correction(title: String, artist: String): GenreCorrection?

    @Query("SELECT * FROM genre_correction")
    fun corrections(): Flow<List<GenreCorrection>>

    @Query("SELECT * FROM genre_observation WHERE observedAtEpochMillis BETWEEN :from AND :to ORDER BY observedAtEpochMillis DESC, id DESC")
    fun observations(from: Long, to: Long): Flow<List<GenreObservation>>

    @Insert
    suspend fun insert(observation: GenreObservation)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(correction: GenreCorrection)

    @Query("DELETE FROM genre_correction WHERE normalizedTitle = :title AND normalizedArtist = :artist")
    suspend fun removeCorrection(title: String, artist: String)

    @Query("DELETE FROM genre_observation WHERE observedAtEpochMillis < :cutoff")
    suspend fun prune(cutoff: Long)

    @Query("DELETE FROM genre_observation")
    suspend fun clearObservations()

    @Query("DELETE FROM genre_correction")
    suspend fun clearCorrections()

    @Transaction
    suspend fun clearAll() {
        clearObservations()
        clearCorrections()
    }
}

@Database(entities = [GenreObservation::class, GenreCorrection::class], version = 1, exportSchema = false)
abstract class GenreHistoryDatabase : RoomDatabase() {
    abstract fun dao(): GenreHistoryDao
}

data class RecentGenreTrack(val title: String, val artist: String, val genre: String, val observedAt: Long)
data class GenreMixItem(val genre: String, val count: Int, val percent: Int)
data class GenreMix(val total: Int, val items: List<GenreMixItem>)

object GenreHistoryMath {
    const val DAY_MS = 86_400_000L

    fun effectiveGenre(observation: GenreObservation, corrections: Map<TrackIdentity, GenreCorrection>): String =
        corrections[TrackIdentity(observation.normalizedTitle, observation.normalizedArtist)]?.correctedGenre
            ?: observation.loggedGenre

    fun recent(rows: List<GenreObservation>, corrections: List<GenreCorrection>, now: Long): List<RecentGenreTrack> {
        val lookup = corrections.associateBy { TrackIdentity(it.normalizedTitle, it.normalizedArtist) }
        return rows.asSequence()
            .filter { it.observedAtEpochMillis in (now - 14 * DAY_MS)..now }
            .sortedWith(compareByDescending<GenreObservation> { it.observedAtEpochMillis }.thenByDescending { it.id })
            .distinctBy { TrackIdentity(it.normalizedTitle, it.normalizedArtist) }
            .take(15)
            .map { RecentGenreTrack(it.title, it.artist, effectiveGenre(it, lookup), it.observedAtEpochMillis) }
            .toList()
    }

    fun mix(rows: List<GenreObservation>, corrections: List<GenreCorrection>, now: Long): GenreMix {
        val lookup = corrections.associateBy { TrackIdentity(it.normalizedTitle, it.normalizedArtist) }
        val counts = rows.asSequence()
            .filter { it.observedAtEpochMillis in (now - 7 * DAY_MS)..now }
            .groupingBy { effectiveGenre(it, lookup).ifBlank { "Other" } }
            .eachCount()
        val total = counts.values.sum()
        if (total == 0) return GenreMix(0, emptyList())
        val ordered = counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        val top = ordered.take(3).map { it.key to it.value }.toMutableList()
        val rest = ordered.drop(3).sumOf { it.value }
        if (rest > 0) {
            val existingOther = top.indexOfFirst { it.first == "Other" }
            if (existingOther >= 0) top[existingOther] = "Other" to (top[existingOther].second + rest)
            else top += "Other" to rest
        }
        return GenreMix(total, top.map { (genre, count) ->
            GenreMixItem(genre, count, (count * 100.0 / total + 0.5).toInt())
        })
    }
}

class GenreHistoryRepository(context: Context) {
    private val dao = Room.databaseBuilder(context.applicationContext, GenreHistoryDatabase::class.java, "genre_history.db")
        .build().dao()

    suspend fun correction(title: String, artist: String): GenreCorrection? {
        val key = TrackIdentity.of(title, artist)
        return if (key.valid) dao.correction(key.title, key.artist) else null
    }

    suspend fun setCorrection(title: String, artist: String, genre: String?) {
        val key = TrackIdentity.of(title, artist)
        require(key.valid)
        if (genre == null) dao.removeCorrection(key.title, key.artist)
        else {
            require(genre in EqProfileManager.correctionGenres)
            dao.upsert(GenreCorrection(key.title, key.artist, title, artist, genre, System.currentTimeMillis()))
        }
    }

    suspend fun record(title: String, artist: String, genre: String, manual: Boolean, at: Long = System.currentTimeMillis()) {
        val key = TrackIdentity.of(title, artist)
        if (!key.valid) return
        dao.insert(GenreObservation(
            observedAtEpochMillis = at,
            title = title, artist = artist,
            normalizedTitle = key.title, normalizedArtist = key.artist,
            loggedGenre = genre, genreOrigin = if (manual) "manual" else "automatic"
        ))
        prune()
    }

    suspend fun prune(now: Long = System.currentTimeMillis()) = dao.prune(now - 16 * GenreHistoryMath.DAY_MS)
    suspend fun clearAll() = dao.clearAll()

    private fun clock(): Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(60_000)
        }
    }

    fun recent(): Flow<List<RecentGenreTrack>> =
        combine(dao.observations(0, Long.MAX_VALUE), dao.corrections(), clock()) { rows, corrections, now ->
            GenreHistoryMath.recent(rows, corrections, now)
        }

    fun mix(): Flow<GenreMix> =
        combine(dao.observations(0, Long.MAX_VALUE), dao.corrections(), clock()) { rows, corrections, now ->
            GenreHistoryMath.mix(rows, corrections, now)
        }
}

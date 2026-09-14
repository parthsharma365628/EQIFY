package com.example.eqify

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.sin

/**
 * Plays a quiet sequence of low, mid and high tones through the music stream.
 * The active system EQ can therefore be checked without bundling an audio asset.
 */
object EqTestTonePlayer {
    private const val SAMPLE_RATE = 44_100
    private const val SEGMENT_SECONDS = 0.55
    private val playing = AtomicBoolean(false)

    suspend fun play(): Boolean = withContext(Dispatchers.IO) {
        if (!playing.compareAndSet(false, true)) return@withContext false
        var track: AudioTrack? = null
        try {
            val frequencies = doubleArrayOf(120.0, 1_000.0, 5_000.0)
            val samplesPerSegment = (SAMPLE_RATE * SEGMENT_SECONDS).toInt()
            val samples = ShortArray(samplesPerSegment * frequencies.size)
            frequencies.forEachIndexed { segment, frequency ->
                repeat(samplesPerSegment) { index ->
                    val edge = minOf(index, samplesPerSegment - index - 1)
                    val fade = (edge / (SAMPLE_RATE * 0.02)).coerceIn(0.0, 1.0)
                    val sample = sin(2.0 * PI * frequency * index / SAMPLE_RATE)
                    samples[segment * samplesPerSegment + index] =
                        (sample * fade * Short.MAX_VALUE * 0.18).toInt().toShort()
                }
            }

            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * Short.SIZE_BYTES)
                .build()

            if (track.state != AudioTrack.STATE_INITIALIZED) return@withContext false
            if (track.write(samples, 0, samples.size) <= 0) return@withContext false
            track.play()
            delay((SEGMENT_SECONDS * frequencies.size * 1_000).toLong() + 100)
            true
        } catch (_: Exception) {
            false
        } finally {
            runCatching { track?.stop() }
            track?.release()
            playing.set(false)
        }
    }
}

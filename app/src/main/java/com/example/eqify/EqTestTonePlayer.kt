package com.example.eqify

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

/** The test sounds (FEATURES_BATCH3_PLAN.md §D). */
enum class TestTone(val label: String) {
    EQ_CHECK("Low, mid and high"),
    LEFT("Left only"),
    RIGHT("Right only"),
    SWEEP("Sweep, low to high")
}

/**
 * Builds the test sounds as interleaved 16-bit stereo. Pure; unit-tested.
 * Every segment fades in and out, so nothing clicks.
 */
object ToneGenerator {
    const val SAMPLE_RATE = 44_100
    private const val LEVEL = 0.18          // quiet, as before
    private const val FADE_SECONDS = 0.02
    const val EQ_SEGMENT_SECONDS = 0.55
    const val CHANNEL_SECONDS = 1.5
    const val SWEEP_SECONDS = 10.0
    const val SWEEP_START_HZ = 20.0
    const val SWEEP_END_HZ = 20_000.0

    fun durationMs(tone: TestTone): Long = when (tone) {
        TestTone.EQ_CHECK -> (EQ_SEGMENT_SECONDS * 3 * 1000).toLong()
        TestTone.LEFT, TestTone.RIGHT -> (CHANNEL_SECONDS * 1000).toLong()
        TestTone.SWEEP -> (SWEEP_SECONDS * 1000).toLong()
    }

    /** Frequency of the sweep at [t] seconds: exponential, so each octave takes equal time. */
    fun sweepHz(t: Double): Double =
        SWEEP_START_HZ * exp(ln(SWEEP_END_HZ / SWEEP_START_HZ) * (t / SWEEP_SECONDS).coerceIn(0.0, 1.0))

    fun generate(tone: TestTone): ShortArray = when (tone) {
        TestTone.EQ_CHECK -> {
            val n = (SAMPLE_RATE * EQ_SEGMENT_SECONDS).toInt()
            val out = ShortArray(n * 3 * 2)
            doubleArrayOf(120.0, 1_000.0, 5_000.0).forEachIndexed { seg, hz ->
                repeat(n) { i ->
                    val v = sample(sin(2 * PI * hz * i / SAMPLE_RATE), i, n)
                    out[(seg * n + i) * 2] = v; out[(seg * n + i) * 2 + 1] = v
                }
            }
            out
        }
        TestTone.LEFT, TestTone.RIGHT -> {
            val n = (SAMPLE_RATE * CHANNEL_SECONDS).toInt()
            val out = ShortArray(n * 2)
            val channel = if (tone == TestTone.LEFT) 0 else 1
            repeat(n) { i -> out[i * 2 + channel] = sample(sin(2 * PI * 440.0 * i / SAMPLE_RATE), i, n) }
            out
        }
        TestTone.SWEEP -> {
            val n = (SAMPLE_RATE * SWEEP_SECONDS).toInt()
            val out = ShortArray(n * 2)
            val k = ln(SWEEP_END_HZ / SWEEP_START_HZ)
            repeat(n) { i ->
                val t = i.toDouble() / SAMPLE_RATE
                // Phase of an exponential sweep, integrated so the pitch rises smoothly.
                val phase = 2 * PI * SWEEP_START_HZ * SWEEP_SECONDS / k * (exp(k * t / SWEEP_SECONDS) - 1)
                val v = sample(sin(phase), i, n)
                out[i * 2] = v; out[i * 2 + 1] = v
            }
            out
        }
    }

    private fun sample(value: Double, index: Int, length: Int): Short {
        val edge = minOf(index, length - index - 1)
        val fade = (edge / (SAMPLE_RATE * FADE_SECONDS)).coerceIn(0.0, 1.0)
        return (value * fade * Short.MAX_VALUE * LEVEL).toInt().toShort()
    }
}

/**
 * Plays a test sound through the music stream, so it passes through the active
 * system EQ. One sound at a time.
 */
object EqTestTonePlayer {
    private val playing = AtomicBoolean(false)
    private val _current = MutableStateFlow<TestTone?>(null)
    val current = _current.asStateFlow()

    suspend fun play(tone: TestTone = TestTone.EQ_CHECK): Boolean = withContext(Dispatchers.IO) {
        if (!playing.compareAndSet(false, true)) return@withContext false
        _current.value = tone
        // A channel test must not be moved by the debug Orbit sweep.
        val holdOrbit = tone == TestTone.LEFT || tone == TestTone.RIGHT
        if (holdOrbit) OrbitPrototype.holdForTest = true
        var track: AudioTrack? = null
        try {
            val samples = ToneGenerator.generate(tone)
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
                        .setSampleRate(ToneGenerator.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * Short.SIZE_BYTES)
                .build()

            // MODE_STATIC starts in STATE_NO_STATIC_DATA until its first write.
            if (track.state == AudioTrack.STATE_UNINITIALIZED) return@withContext false
            if (track.write(samples, 0, samples.size) != samples.size) return@withContext false
            if (track.state != AudioTrack.STATE_INITIALIZED) return@withContext false
            track.play()
            delay(ToneGenerator.durationMs(tone) + 100)
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("EqTestTonePlayer", "Test playback failed", e)
            false
        } finally {
            runCatching { track?.stop() }
            runCatching { track?.release() }
            if (holdOrbit) OrbitPrototype.holdForTest = false
            _current.value = null
            playing.set(false)
        }
    }
}

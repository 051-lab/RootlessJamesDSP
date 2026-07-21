package me.timschneeberger.rootlessjamesdsp.dsp

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.timschneeberger.rootlessjamesdsp.interop.JdspImpResToolbox
import me.timschneeberger.rootlessjamesdsp.interop.JamesDspLocalEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class AudioOutputStageInstrumentedTest {
    @Test
    fun harmonicControlAddsHarmonicsWithoutDcOffset() {
        val clean = measureHarmonics(0f)
        val enhanced = measureHarmonics(1f)

        Log.i(TAG, "harmonics: clean=$clean, enhanced=$enhanced")
        assertTrue(abs(clean.mean) < MAX_DC)
        assertTrue(abs(enhanced.mean) < MAX_DC)
        assertTrue(enhanced.thd > clean.thd + MIN_THD_INCREASE)
    }

    @Test
    fun limiterDetectsIntersamplePeakBelowSampleThreshold() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        JamesDspLocalEngine(context, reportSampleRate = false).use { engine ->
            engine.sampleRate = SAMPLE_RATE.toFloat()
            assertTrue(engine.setOutputControl(TRUE_PEAK_THRESHOLD_DB, 60f, 0f))

            val output = processSine(
                engine,
                frequency = SAMPLE_RATE / 4.0,
                amplitude = 1f,
                phase = PI / 4.0,
            )
            val samplePeak = output.maxOf { abs(it) }
            val reduction = engine.getLimiterGainReduction()

            Log.i(TAG, "true peak: outputSamplePeak=$samplePeak, reduction=$reduction")
            assertTrue(reduction > MIN_TRUE_PEAK_REDUCTION_DB)
            assertTrue(samplePeak <= dbToLinear(TRUE_PEAK_THRESHOLD_DB) + PEAK_TOLERANCE)
        }
    }

    @Test
    fun disabledOutputStageIsTransparent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        JamesDspLocalEngine(context, reportSampleRate = false).use { engine ->
            engine.sampleRate = SAMPLE_RATE.toFloat()
            assertTrue(engine.setOutputControl(-12f, 60f, 0f))
            assertTrue(engine.setOutputLimiterEnabled(false))
            val input = FloatArray(BLOCK_FRAMES * 2) { index ->
                sin(2.0 * PI * 997.0 * (index / 2) / SAMPLE_RATE).toFloat() * 0.9f
            }
            val output = FloatArray(input.size)

            engine.processFloat(input, output)

            assertEquals(0f, input.zip(output).maxOf { (expected, actual) -> abs(expected - actual) }, 0f)
        }
    }

    @Test
    fun partialBlocksDoNotReconfigureConvolution() {
        val fixed = processConvolution(intArrayOf(128))
        val variable = processConvolution(intArrayOf(64, 96, 32, 128, 48, 80))
        val difference = fixed.indices.maxOf { abs(fixed[it] - variable[it]) }

        assertTrue(fixed.any { abs(it) > 0.001f })
        assertTrue("maximum stream difference was $difference", difference < 0.0001f)
    }

    @Test
    fun loadsMonoMinimumPhaseImpulse() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("mono-ir-", ".wav", context.cacheDir)
        try {
            writeMonoWav(file, ShortArray(64).apply { this[0] = Short.MAX_VALUE })

            val info = IntArray(4)
            val impulse = JdspImpResToolbox.ReadImpulseResponseToFloat(
                file.absolutePath,
                SAMPLE_RATE,
                info,
                2,
                intArrayOf(-80, -100, 0, 0, 0, 0),
            )

            assertNotNull(impulse)
            assertEquals(1, info[0])
            assertEquals(info[1], impulse!!.size)
            assertTrue(impulse.all(Float::isFinite))
        } finally {
            file.delete()
        }
    }

    @Test
    fun rejectsShiftOutsideTrimmedImpulseLength() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("trimmed-mono-ir-", ".wav", context.cacheDir)
        try {
            writeMonoWav(file, ShortArray(64).apply {
                this[24] = Short.MAX_VALUE
                this[27] = (Short.MAX_VALUE / 2).toShort()
            })
            val info = IntArray(4)
            val advanced = intArrayOf(-80, -100, 32, 0, 0, 0)

            val impulse = JdspImpResToolbox.ReadImpulseResponseToFloat(
                file.absolutePath,
                SAMPLE_RATE,
                info,
                1,
                advanced,
            )

            assertNotNull(impulse)
            assertEquals(1, info[0])
            assertEquals(info[1], impulse!!.size)
            assertEquals(0, info[3])
            assertEquals(0, advanced[2])
            assertTrue(impulse.all(Float::isFinite))
        } finally {
            file.delete()
        }
    }

    @Test
    fun rejectsMalformedOrNonFiniteConfiguration() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        JamesDspLocalEngine(context, reportSampleRate = false).use { engine ->
            assertFalse(engine.setMultiEqualizer(true, 0, 0, List(29) { "0" }.joinToString(";")))
            assertFalse(engine.setMultiEqualizer(true, 0, 0, List(31) { "0" }.joinToString(";")))
            assertFalse(engine.setCompander(true, 0.22f, 0, 0, List(13) { "0" }.joinToString(";")))
            assertFalse(engine.setCompander(true, 0.22f, 0, 0, List(15) { "0" }.joinToString(";")))
            assertFalse(engine.setOutputControl(Float.NaN, 60f, 0f))
            assertFalse(engine.setVacuumTubeHarmonicGain(Float.POSITIVE_INFINITY))
        }
    }

    private fun measureHarmonics(amount: Float): HarmonicMeasurement {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return JamesDspLocalEngine(context, reportSampleRate = false).use { engine ->
            engine.sampleRate = SAMPLE_RATE.toFloat()
            assertTrue(engine.setOutputControl(-0.1f, 60f, 0f))
            assertTrue(engine.setVacuumTube(true, 0f))
            assertTrue(engine.setVacuumTubeHarmonicGain(amount))

            val output = processSine(engine, frequency = 1_000.0, amplitude = 0.25f)
            val analysis = output.copyOfRange(SAMPLE_RATE * 2, output.size)
            val mean = analysis.filterIndexed { index, _ -> index % 2 == 0 }.average()
            val fundamental = magnitude(analysis, 1_000.0)
            val harmonicPower = (2..5).sumOf { harmonic ->
                val value = magnitude(analysis, 1_000.0 * harmonic)
                value * value
            }
            HarmonicMeasurement(mean, kotlin.math.sqrt(harmonicPower) / fundamental)
        }
    }

    private fun processConvolution(blockFrames: IntArray): FloatArray {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return JamesDspLocalEngine(context, reportSampleRate = false).use { engine ->
            engine.sampleRate = SAMPLE_RATE.toFloat()
            assertTrue(engine.reserveProcessingFrames(256))
            assertTrue(engine.setOutputLimiterEnabled(false))
            assertTrue(engine.setConvolverCoefficients(FloatArray(256) { index ->
                if (index < 16) (16 - index) / 136f else 0f
            }, 1))

            val input = FloatArray(STREAM_FRAMES * 2) { index ->
                sin(2.0 * PI * 997.0 * (index / 2) / SAMPLE_RATE).toFloat() * 0.25f
            }
            val result = FloatArray(input.size)
            val output = FloatArray(blockFrames.max() * 2)
            var frameOffset = 0
            var blockIndex = 0
            while (frameOffset < STREAM_FRAMES) {
                val frames = minOf(blockFrames[blockIndex++ % blockFrames.size], STREAM_FRAMES - frameOffset)
                engine.processFloat(input, output, frameOffset * 2, frames * 2)
                output.copyInto(result, frameOffset * 2, 0, frames * 2)
                frameOffset += frames
            }
            result
        }
    }

    private fun processSine(
        engine: JamesDspLocalEngine,
        frequency: Double,
        amplitude: Float,
        phase: Double = 0.0,
        seconds: Int = 2,
    ): FloatArray {
        val frameCount = SAMPLE_RATE * seconds
        val result = FloatArray(frameCount * 2)
        val input = FloatArray(BLOCK_FRAMES * 2)
        val output = FloatArray(BLOCK_FRAMES * 2)
        var frameOffset = 0
        while (frameOffset < frameCount) {
            val frames = minOf(BLOCK_FRAMES, frameCount - frameOffset)
            for (frame in 0 until frames) {
                val sample = amplitude * sin(
                    2.0 * PI * frequency * (frameOffset + frame) / SAMPLE_RATE + phase
                ).toFloat()
                input[frame * 2] = sample
                input[frame * 2 + 1] = sample
            }
            engine.processFloat(input, output, 0, frames * 2)
            output.copyInto(result, frameOffset * 2, 0, frames * 2)
            frameOffset += frames
        }
        return result
    }

    private fun writeMonoWav(file: File, samples: ShortArray) {
        val dataBytes = samples.size * Short.SIZE_BYTES
        val wav = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + dataBytes)
            put("WAVEfmt ".toByteArray())
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(SAMPLE_RATE)
            putInt(SAMPLE_RATE * Short.SIZE_BYTES)
            putShort(Short.SIZE_BYTES.toShort())
            putShort(16)
            put("data".toByteArray())
            putInt(dataBytes)
            samples.forEach(::putShort)
        }
        file.writeBytes(wav.array())
    }

    private fun magnitude(interleaved: FloatArray, frequency: Double): Double {
        val frames = interleaved.size / 2
        var real = 0.0
        var imaginary = 0.0
        for (frame in 0 until frames) {
            val phase = 2.0 * PI * frequency * frame / SAMPLE_RATE
            val sample = interleaved[frame * 2].toDouble()
            real += sample * cos(phase)
            imaginary -= sample * sin(phase)
        }
        return 2.0 * hypot(real, imaginary) / frames
    }

    private fun dbToLinear(decibels: Float): Float =
        10.0.pow(decibels / 20.0).toFloat()

    private data class HarmonicMeasurement(val mean: Double, val thd: Double)

    companion object {
        private const val TAG = "AudioOutputStageTest"
        private const val SAMPLE_RATE = 48_000
        private const val BLOCK_FRAMES = 1_024
        private const val STREAM_FRAMES = 4_096
        private const val MAX_DC = 0.0001
        private const val MIN_THD_INCREASE = 0.0001
        private const val TRUE_PEAK_THRESHOLD_DB = -1f
        private const val MIN_TRUE_PEAK_REDUCTION_DB = 0.5f
        private const val PEAK_TOLERANCE = 0.001f
    }
}

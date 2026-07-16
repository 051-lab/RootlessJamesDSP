package me.timschneeberger.rootlessjamesdsp.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

object DarwinOversampling {
    const val HEADROOM_DB = 1f

    fun harmonicAmount(percent: Float): Float =
        ((percent.takeIf(Float::isFinite) ?: 0f).coerceIn(0f, 100f) / 100f).let { it * it }

    fun harmonicHeadroomScale(amount: Float): Float =
        1f / (1f + MAX_HARMONIC_ADDITION *
            (amount.takeIf(Float::isFinite) ?: 0f).coerceIn(0f, 1f))

    fun applyGain(samples: FloatArray, gain: Float): FloatArray =
        samples.copyOf().apply { for (index in indices) this[index] *= gain }

    fun headroomScale(samples: FloatArray): Float {
        require(samples.isNotEmpty() && samples.all(Float::isFinite))
        var peak = 0.0
        for (bin in 0..RESPONSE_BINS) {
            val omega = PI * bin / RESPONSE_BINS
            var real = 0.0
            var imaginary = 0.0
            for (tap in samples.indices) {
                real += samples[tap] * cos(omega * tap)
                imaginary -= samples[tap] * sin(omega * tap)
            }
            peak = maxOf(peak, hypot(real, imaginary))
        }
        require(peak.isFinite() && peak > 0.0)
        return min(1.0, 10.0.pow(-HEADROOM_DB / 20.0) / peak).toFloat()
    }

    fun fadeHead(samples: FloatArray, frames: Int, sampleCount: Int = samples.size) =
        applyFade(samples, 0, min(frames, sampleCount / 2), true)

    fun fadeTail(samples: FloatArray, frames: Int, sampleCount: Int = samples.size) {
        val fadeFrames = min(frames, sampleCount / 2)
        applyFade(samples, sampleCount / 2 - fadeFrames, fadeFrames, false)
    }

    fun fadeHead(samples: ShortArray, frames: Int, sampleCount: Int = samples.size) =
        applyFade(samples, 0, min(frames, sampleCount / 2), true)

    fun fadeTail(samples: ShortArray, frames: Int, sampleCount: Int = samples.size) {
        val fadeFrames = min(frames, sampleCount / 2)
        applyFade(samples, sampleCount / 2 - fadeFrames, fadeFrames, false)
    }

    private fun applyFade(samples: FloatArray, startFrame: Int, frames: Int, fadeIn: Boolean) {
        if (frames <= 0) return
        for (offset in 0 until frames) {
            val gain = if (frames == 1) 0f else {
                val position = offset.toFloat() / (frames - 1)
                if (fadeIn) position else 1f - position
            }
            val frame = startFrame + offset
            samples[frame * 2] *= gain
            samples[frame * 2 + 1] *= gain
        }
    }

    private fun applyFade(samples: ShortArray, startFrame: Int, frames: Int, fadeIn: Boolean) {
        if (frames <= 0) return
        for (offset in 0 until frames) {
            val gain = if (frames == 1) 0f else {
                val position = offset.toFloat() / (frames - 1)
                if (fadeIn) position else 1f - position
            }
            val frame = startFrame + offset
            samples[frame * 2] = (samples[frame * 2] * gain).roundToInt().toShort()
            samples[frame * 2 + 1] = (samples[frame * 2 + 1] * gain).roundToInt().toShort()
        }
    }

    private const val RESPONSE_BINS = 2048
    private const val MAX_HARMONIC_ADDITION = 0.2f
}

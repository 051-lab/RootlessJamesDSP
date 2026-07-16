package me.timschneeberger.rootlessjamesdsp.dsp

import kotlin.math.roundToInt

class Pcm16Converter(seed: Int = System.nanoTime().toInt()) {
    private var randomState = seed.takeUnless { it == 0 } ?: DEFAULT_SEED

    fun decode(input: ShortArray, output: FloatArray, size: Int = input.size) {
        require(size in 0..minOf(input.size, output.size))
        for (index in 0 until size)
            output[index] = input[index] / PCM_SCALE
    }

    fun encode(input: FloatArray, output: ShortArray, size: Int = input.size) {
        require(size in 0..minOf(input.size, output.size))
        for (index in 0 until size) {
            val sample = input[index].takeIf(Float::isFinite) ?: 0f
            val dither = nextUnitFloat() - nextUnitFloat()
            output[index] = (sample * PCM_SCALE + dither)
                .roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
    }

    private fun nextUnitFloat(): Float {
        var value = randomState
        value = value xor (value shl 13)
        value = value xor (value ushr 17)
        value = value xor (value shl 5)
        randomState = value
        return (value ushr 8) * UNIT_SCALE
    }

    private companion object {
        const val PCM_SCALE = 32768f
        const val UNIT_SCALE = 1f / 16777216f
        const val DEFAULT_SEED = 0x6D2B79F5
    }
}

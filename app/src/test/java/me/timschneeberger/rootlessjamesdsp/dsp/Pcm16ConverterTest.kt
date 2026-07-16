package me.timschneeberger.rootlessjamesdsp.dsp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Pcm16ConverterTest {
    @Test
    fun decodesFullPcm16RangeToFloat() {
        val output = FloatArray(3)

        Pcm16Converter(1).decode(shortArrayOf(Short.MIN_VALUE, 0, Short.MAX_VALUE), output)

        assertArrayEquals(floatArrayOf(-1f, 0f, 32767f / 32768f), output, 0f)
    }

    @Test
    fun encodesWithBoundedDeterministicTpdfDither() {
        val input = floatArrayOf(Float.NEGATIVE_INFINITY, -1.5f, 0f, 0f, 1.5f, Float.NaN)
        val first = ShortArray(input.size)
        val second = ShortArray(input.size)

        Pcm16Converter(1234).encode(input, first)
        Pcm16Converter(1234).encode(input, second)

        assertArrayEquals(first, second)
        assertTrue(first[1] == Short.MIN_VALUE)
        assertTrue(first[4] == Short.MAX_VALUE)
        assertTrue(first[0].toInt() in -1..1)
        assertTrue(first[2].toInt() in -1..1)
        assertTrue(first[3].toInt() in -1..1)
        assertTrue(first[5].toInt() in -1..1)
    }
}

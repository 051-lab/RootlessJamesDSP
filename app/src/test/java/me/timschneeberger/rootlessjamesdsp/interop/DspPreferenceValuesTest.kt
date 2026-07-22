package me.timschneeberger.rootlessjamesdsp.interop

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DspPreferenceValuesTest {
    @Test
    fun equalizerAndCompanderRequireExactTokenCounts() {
        assertEquals(30, parseFiniteDoubles(values(30), 30)?.size)
        assertNull(parseFiniteDoubles(values(29), 30))
        assertNull(parseFiniteDoubles(values(31), 30))

        assertEquals(14, parseFiniteDoubles(values(14), 14)?.size)
        assertNull(parseFiniteDoubles(values(13), 14))
        assertNull(parseFiniteDoubles(values(15), 14))
    }

    @Test
    fun coefficientListsRejectMalformedAndNonFiniteValues() {
        assertNull(parseFiniteDoubles("1;not-a-number;3", 3))
        assertNull(parseFiniteDoubles("1;NaN;3", 3))
        assertNull(parseFiniteDoubles("1;Infinity;3", 3))
        val parsed = requireNotNull(parseFiniteDoubles("1;-2.5;3e-2", 3))
        assertArrayEquals(doubleArrayOf(1.0, -2.5, 0.03), parsed, 0.0)
    }

    @Test
    fun integerPreferencesFallbackAndClamp() {
        assertEquals(2, parseClampedInt("not-an-int", 2, 0..5))
        assertEquals(2, parseClampedInt("999999999999999999", 2, 0..5))
        assertEquals(0, parseClampedInt("-8", 2, 0..5))
        assertEquals(5, parseClampedInt("8", 2, 0..5))
        assertEquals(3, parseClampedInt("3", 2, 0..5))
    }

    @Test
    fun floatConfigurationsDefaultNonFiniteValuesAndClampFiniteValues() {
        assertEquals(60f, sanitizeFiniteFloat(Float.NaN, 60f, 1.5f..500f), 0f)
        assertEquals(60f, sanitizeFiniteFloat(Float.POSITIVE_INFINITY, 60f, 1.5f..500f), 0f)
        assertEquals(60f, sanitizeFiniteFloat(Float.NEGATIVE_INFINITY, 60f, 1.5f..500f), 0f)
        assertEquals(1.5f, sanitizeFiniteFloat(-1f, 60f, 1.5f..500f), 0f)
        assertEquals(500f, sanitizeFiniteFloat(900f, 60f, 1.5f..500f), 0f)
        assertEquals(75f, sanitizeFiniteFloat(75f, 60f, 1.5f..500f), 0f)
    }

    private fun values(count: Int) = (0 until count).joinToString(";")
}

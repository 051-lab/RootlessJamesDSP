package me.timschneeberger.rootlessjamesdsp.dsp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class DarwinOversamplingTest {
    @Test
    fun reservesHeadroomForFilterPeakGain() {
        val unityScale = DarwinOversampling.headroomScale(floatArrayOf(1f))
        val boostedScale = DarwinOversampling.headroomScale(floatArrayOf(1.5f, -0.5f))

        assertEquals(10.0.pow(-DarwinOversampling.HEADROOM_DB / 20.0).toFloat(), unityScale, 0.0001f)
        assertTrue(boostedScale < unityScale)
        assertTrue(DarwinOversampling.applyGain(floatArrayOf(1.5f, -0.5f), boostedScale)
            .all(Float::isFinite))
    }

    @Test
    fun tapersHarmonicControlAndReservesNonlinearHeadroom() {
        assertEquals(0f, DarwinOversampling.harmonicAmount(0f), 0f)
        assertEquals(0.25f, DarwinOversampling.harmonicAmount(50f), 0f)
        assertEquals(1f, DarwinOversampling.harmonicAmount(100f), 0f)
        assertEquals(0f, DarwinOversampling.harmonicAmount(Float.NaN), 0f)
        assertEquals(1f, DarwinOversampling.harmonicHeadroomScale(0f), 0f)
        assertEquals(1f, DarwinOversampling.harmonicHeadroomScale(Float.POSITIVE_INFINITY), 0f)
        assertEquals(1f / 1.2f, DarwinOversampling.harmonicHeadroomScale(1f), 0.0001f)
    }

    @Test
    fun rampsStereoBlockEdges() {
        val fadeIn = FloatArray(8) { 1f }
        val fadeOut = ShortArray(8) { 100 }

        DarwinOversampling.fadeHead(fadeIn, 2)
        DarwinOversampling.fadeTail(fadeOut, 2)

        assertArrayEquals(floatArrayOf(0f, 0f, 1f, 1f, 1f, 1f, 1f, 1f), fadeIn, 0f)
        assertArrayEquals(shortArrayOf(100, 100, 100, 100, 100, 100, 0, 0), fadeOut)
    }
}

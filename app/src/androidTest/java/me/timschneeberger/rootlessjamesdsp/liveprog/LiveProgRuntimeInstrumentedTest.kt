package me.timschneeberger.rootlessjamesdsp.liveprog

import androidx.test.ext.junit.runners.AndroidJUnit4
import me.timschneeberger.rootlessjamesdsp.interop.JamesDspWrapper
import me.timschneeberger.rootlessjamesdsp.model.ProcessorMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveProgRuntimeInstrumentedTest {
    @Test
    fun parsesListPropertiesWithAndroidRegexEngine() {
        val parser = EelParser().apply {
            contents = """
                mode:1<0,2,1{Off, Normal, Wide}>Mode
                @init
                mode = 1;
                @sample
                spl0 = spl0;
                spl1 = spl1;
            """.trimIndent()
            parse()
        }

        val property = parser.properties.single() as EelListProperty
        assertEquals(listOf("Off", "Normal", "Wide"), property.options)
    }

    @Test
    fun executesSliderAndBlockAtTheirExpectedLifecyclePoints() {
        val callbacks = RecordingCallbacks()
        val handle = JamesDspWrapper.alloc(callbacks)
        assertNotEquals(0L, handle)

        try {
            assertTrue(JamesDspWrapper.setLimiterEnabled(handle, false))
            JamesDspWrapper.setSamplingRate(handle, 48_000f, false)
            assertTrue(
                JamesDspWrapper.setLiveprog(
                    handle,
                    true,
                    "lifecycle-test",
                    """
                    @init
                    block_count = 0;
                    slider_count = 0;
                    slider1 = 0.5;
                    @slider
                    slider_count += 1;
                    gain = slider1 + slider_count * 0.001;
                    @block
                    block_count += 1;
                    block_size = samplesblock;
                    @sample
                    spl0 = spl0 * gain + block_count * 0.01;
                    spl1 = spl1 * gain + block_size * 0.000001;
                    """.trimIndent(),
                )
            )
            assertEquals(1, callbacks.resultCode)

            val input = FloatArray(16) { 0.2f }
            val output = FloatArray(input.size)

            JamesDspWrapper.processFloat(handle, input, output)
            assertStereoBlock(output, 0.1102f, 0.100208f)

            JamesDspWrapper.processFloat(handle, input, output)
            assertStereoBlock(output, 0.1202f, 0.100208f)

            assertTrue(JamesDspWrapper.manipulateEelVariable(handle, "slider1", 0.25f))
            JamesDspWrapper.processFloat(handle, input, output)
            assertStereoBlock(output, 0.0804f, 0.050408f)

            assertFalse(JamesDspWrapper.manipulateEelVariable(handle, "unknown_slider", 1f))
        } finally {
            JamesDspWrapper.free(handle)
        }
    }

    @Test
    fun acceptsBlockAndSampleWithoutInit() {
        val callbacks = RecordingCallbacks()
        val handle = JamesDspWrapper.alloc(callbacks)
        assertNotEquals(0L, handle)

        try {
            assertTrue(JamesDspWrapper.setLimiterEnabled(handle, false))
            assertTrue(
                JamesDspWrapper.setLiveprog(
                    handle,
                    true,
                    "optional-init-test",
                    """
                    @block
                    block_count += 1;
                    @sample
                    spl0 = block_count * 0.01;
                    spl1 = samplesblock * 0.001;
                    """.trimIndent(),
                )
            )
            assertEquals(1, callbacks.resultCode)

            val output = FloatArray(8)
            JamesDspWrapper.processFloat(handle, FloatArray(output.size), output)
            assertStereoBlock(output, 0.01f, 0.004f)
        } finally {
            JamesDspWrapper.free(handle)
        }
    }

    @Test
    fun updatesSrateWhenEngineRateChanges() {
        val callbacks = RecordingCallbacks()
        val handle = JamesDspWrapper.alloc(callbacks)
        assertNotEquals(0L, handle)

        try {
            assertTrue(JamesDspWrapper.setLimiterEnabled(handle, false))
            JamesDspWrapper.setSamplingRate(handle, 48_000f, false)
            assertTrue(JamesDspWrapper.setLiveprog(
                handle,
                true,
                "sample-rate-test",
                "@sample\nspl0 = srate / 100000; spl1 = spl0;",
            ))
            val output = FloatArray(8)
            JamesDspWrapper.processFloat(handle, FloatArray(output.size), output)
            assertStereoBlock(output, 0.48f, 0.48f)

            JamesDspWrapper.setSamplingRate(handle, 44_100f, false)
            JamesDspWrapper.processFloat(handle, FloatArray(output.size), output)
            assertStereoBlock(output, 0.441f, 0.441f)
        } finally {
            JamesDspWrapper.free(handle)
        }
    }

    @Test
    fun reportsSectionSpecificParseFailures() {
        val callbacks = RecordingCallbacks()
        val handle = JamesDspWrapper.alloc(callbacks)
        assertNotEquals(0L, handle)

        try {
            assertLoadResult(
                handle,
                callbacks,
                "@slider\ngain = ;\n@sample\nspl0 = spl0;",
                -4,
            )
            assertLoadResult(
                handle,
                callbacks,
                "@block\nblock_value = ;\n@sample\nspl0 = spl0;",
                -5,
            )
            assertLoadResult(
                handle,
                callbacks,
                "@sample\nspl0 = spl0;\n@sample\nspl1 = spl1;",
                -6,
            )
            assertLoadResult(handle, callbacks, "@init\nvalue = 1;", -2)
        } finally {
            JamesDspWrapper.free(handle)
        }
    }

    @Test
    fun failedReloadPreservesTheActiveProgram() {
        val callbacks = RecordingCallbacks()
        val handle = JamesDspWrapper.alloc(callbacks)
        assertNotEquals(0L, handle)

        try {
            assertTrue(JamesDspWrapper.setLimiterEnabled(handle, false))
            assertTrue(JamesDspWrapper.setLiveprog(
                handle,
                true,
                "first",
                "@sample\nspl0 = 0.25; spl1 = 0.25;",
            ))
            val output = FloatArray(8)
            JamesDspWrapper.processFloat(handle, FloatArray(output.size), output)
            assertStereoBlock(output, 0.25f, 0.25f)

            assertFalse(JamesDspWrapper.setLiveprog(
                handle,
                true,
                "invalid",
                "@sample\nspl0 = ; spl1 = 0;",
            ))
            assertEquals(-3, callbacks.resultCode)
            JamesDspWrapper.processFloat(handle, FloatArray(output.size), output)
            assertStereoBlock(output, 0.25f, 0.25f)

            assertTrue(JamesDspWrapper.setLiveprog(
                handle,
                true,
                "second",
                "@sample\nspl0 = 0.5; spl1 = 0.5;",
            ))
            JamesDspWrapper.processFloat(handle, FloatArray(output.size), output)
            assertStereoBlock(output, 0.5f, 0.5f)
        } finally {
            JamesDspWrapper.free(handle)
        }
    }

    private fun assertLoadResult(
        handle: Long,
        callbacks: RecordingCallbacks,
        script: String,
        expectedResult: Int,
    ) {
        assertFalse(JamesDspWrapper.setLiveprog(handle, true, "parse-test", script))
        assertEquals(expectedResult, callbacks.resultCode)
    }

    private fun assertStereoBlock(output: FloatArray, left: Float, right: Float) {
        for (frame in output.indices step 2) {
            assertEquals(left, output[frame], 0.00001f)
            assertEquals(right, output[frame + 1], 0.00001f)
        }
    }

    private class RecordingCallbacks : JamesDspWrapper.JamesDspCallbacks {
        var resultCode: Int? = null

        override fun onLiveprogOutput(message: String) = Unit
        override fun onLiveprogExec(id: String) = Unit
        override fun onLiveprogResult(resultCode: Int, id: String, errorMessage: String?) {
            this.resultCode = resultCode
        }

        override fun onVdcParseError() = Unit
        override fun onConvolverParseError(errorCode: ProcessorMessage.ConvolverErrorCode) = Unit
    }
}

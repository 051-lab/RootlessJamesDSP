package me.timschneeberger.rootlessjamesdsp.utils.preferences

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class DspPreferenceStoreInstrumentedTest {
    @Test
    fun parsesSupportedSharedPreferenceTypes() {
        withPreferenceDirectory { directory ->
            val file = File(directory, "$TEST_NAMESPACE.xml")
            file.writeText(
                """<?xml version="1.0" encoding="utf-8"?>
                    <map>
                        <string name="text">value</string>
                        <int name="integer" value="7" />
                        <long name="long" value="8" />
                        <float name="float" value="1.5" />
                        <boolean name="boolean" value="true" />
                        <set name="set"><string>first</string><string>second</string></set>
                    </map>
                """.trimIndent()
            )

            val values = DspPreferenceStore.read(file)

            assertEquals("value", values["text"])
            assertEquals(7, values["integer"])
            assertEquals(8L, values["long"])
            assertEquals(1.5f, values["float"])
            assertEquals(true, values["boolean"])
            assertEquals(setOf("first", "second"), values["set"])
        }
    }

    @Test
    fun updatesAnAlreadyLoadedPreferenceInstance() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        val livePreferences = context.getSharedPreferences(
            TEST_NAMESPACE,
            Context.MODE_MULTI_PROCESS
        )
        livePreferences.edit().putString("text", "before").commit()

        try {
            withPreferenceDirectory { directory ->
                File(directory, "$TEST_NAMESPACE.xml").writeText(
                    """<?xml version="1.0" encoding="utf-8"?>
                        <map><string name="text">after</string></map>
                    """.trimIndent()
                )

                DspPreferenceStore.restore(context, directory, clearExisting = false)

                assertEquals("after", livePreferences.getString("text", null))
            }
        } finally {
            livePreferences.edit().clear().commit()
        }
    }

    @Test
    fun rejectsMalformedPreferenceXml() {
        withPreferenceDirectory { directory ->
            val file = File(directory, "$TEST_NAMESPACE.xml")
            file.writeText("<map><unknown name=\"value\" /></map>")

            var rejected = false
            try {
                DspPreferenceStore.read(file)
            } catch (_: IOException) {
                rejected = true
            }

            assertTrue(rejected)
        }
    }

    private fun withPreferenceDirectory(block: (File) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "preference-import-test").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    companion object {
        private const val TEST_NAMESPACE = "dsp_instrumentation_restore"
    }
}

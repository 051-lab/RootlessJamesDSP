package me.timschneeberger.rootlessjamesdsp.dsp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class DarwinFilterPackageInstrumentedTest {
    @Test
    fun loadsDarwinPackageOnAndroid() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val archive = File(context.cacheDir, "darwin-filter-test.zip")
        val paths = (1..11).map { "filter-$it.flt" }
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("filter.json"))
            zip.write(paths.mapIndexed { index, path ->
                """{"title":"Filter ${index + 1}","path":"$path"}"""
            }.joinToString(",", "{\"list\":[", "]}").toByteArray())
            zip.closeEntry()

            val coefficients = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN).apply {
                repeat(256) { putInt(it + 1) }
            }.array()
            paths.forEach { path ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(coefficients)
                zip.closeEntry()
            }
        }

        try {
            val filters = DarwinFilterPackage.list(archive)

            assertEquals(11, filters.size)
            filters.forEach { filter ->
                val impulse = DarwinFilterPackage.read(archive, filter.fileName)
                assertEquals(256, impulse.samples.size)
                assertEquals(1.0f, impulse.samples.sum(), 0.0001f)
            }
        } finally {
            archive.delete()
        }
    }
}

package me.timschneeberger.rootlessjamesdsp.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DarwinFilterPackageTest {
    @Test
    fun parsesManifestOrderAndForwardCoefficients() {
        val paths = (1..11).map { "filter-$it.flt" }
        val file = packageFile(paths, coefficientBytes { it + 1 })

        val filters = DarwinFilterPackage.list(file)
        assertEquals(11, filters.size)
        assertEquals(paths, filters.map { it.fileName })
        filters.forEach {
            val impulse = DarwinFilterPackage.read(file, it.fileName)
            assertEquals(256, impulse.samples.size)
            assertEquals(1.0f, impulse.samples.sum(), 0.0001f)
        }
        val first = DarwinFilterPackage.read(file, filters.first().fileName).samples
        assertEquals(first[0] * 2f, first[1], 0.000001f)
    }

    @Test
    fun acceptsHibyManifestPathAndResolvesArchiveBasename() {
        val file = packageFile("/mnt/sdcard/filter/filter.flt", coefficientBytes())

        val filter = DarwinFilterPackage.list(file).single()
        val impulse = DarwinFilterPackage.read(file, filter.fileName)

        assertEquals("filter.flt", filter.fileName)
        assertEquals(256, impulse.samples.size)
        assertEquals(1.0f, impulse.samples.sum(), 0.0001f)
    }

    @Test
    fun rejectsMalformedPackages() {
        assertThrows(IllegalArgumentException::class.java) { DarwinFilterPackage.list(packageFile(null)) }
        assertThrows(IllegalArgumentException::class.java) {
            DarwinFilterPackage.list(packageFile("../filter.flt", coefficientBytes()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DarwinFilterPackage.list(packageFile("/filter.flt", coefficientBytes()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DarwinFilterPackage.list(packageFile("sub/filter.flt", coefficientBytes()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DarwinFilterPackage.list(packageFile("/mnt/sdcard/filter/../filter.flt", coefficientBytes()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DarwinFilterPackage.list(packageFile("filter.flt", ByteArray(1020)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DarwinFilterPackage.list(packageFile("filter.flt", null))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DarwinFilterPackage.read(packageFile("filter.flt", coefficientBytes(0)), "filter.flt")
        }
        assertThrows(IllegalArgumentException::class.java) {
            DarwinFilterPackage.read(
                packageFile("filter.flt", coefficientBytes { index ->
                    when (index) {
                        0 -> 1_000
                        1 -> -997
                        else -> 0
                    }
                }),
                "filter.flt",
            )
        }
    }

    private fun packageFile(path: String?, coefficients: ByteArray? = null): File {
        return packageFile(path?.let(::listOf).orEmpty(), coefficients)
    }

    private fun packageFile(paths: List<String>, coefficients: ByteArray?): File {
        val file = kotlin.io.path.createTempFile(suffix = ".zip").toFile().apply { deleteOnExit() }
        ZipOutputStream(file.outputStream()).use { zip ->
            if (paths.isNotEmpty()) {
                zip.putNextEntry(ZipEntry("filter.json"))
                val list = paths.mapIndexed { index, path ->
                    """{"title":"Filter ${index + 1}","path":"$path"}"""
                }.joinToString(",")
                zip.write("""{"list":[$list]}""".toByteArray())
                zip.closeEntry()
            }
            if (coefficients != null) {
                paths.forEach { path ->
                    zip.putNextEntry(ZipEntry(path.replace('\\', '/').substringAfterLast('/')))
                    zip.write(coefficients)
                    zip.closeEntry()
                }
            }
        }
        return file
    }

    private fun coefficientBytes(value: Int = 1): ByteArray =
        coefficientBytes { value }

    private fun coefficientBytes(value: (Int) -> Int): ByteArray =
        ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(256) { putInt(value(it)) }
        }.array()
}

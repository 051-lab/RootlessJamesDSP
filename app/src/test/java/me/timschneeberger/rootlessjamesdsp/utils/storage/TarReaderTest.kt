package me.timschneeberger.rootlessjamesdsp.utils.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.kamranzafar.jtar.TarEntry
import org.kamranzafar.jtar.TarOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.io.path.createTempDirectory

class TarReaderTest {
    @Test
    fun rejectsTraversalWithoutWritingOutsideExtractionRoot() {
        val folder = createTempDirectory("tar-reader-").toFile()
        try {
            val source = folder.resolve("source").apply { writeText("unsafe") }
            val archive = ByteArrayOutputStream().also { output ->
                TarOutputStream(output).use { tar ->
                    tar.putNextEntry(TarEntry(source, "../escaped"))
                    source.inputStream().use { it.copyTo(tar) }
                }
            }.toByteArray()

            assertNull(Tar.Reader(ByteArrayInputStream(archive)).extract(folder.resolve("target")))
            assertFalse(folder.resolve("escaped").exists())
        } finally {
            folder.deleteRecursively()
        }
    }
}

package me.timschneeberger.rootlessjamesdsp.dsp

import android.annotation.SuppressLint
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.ZipFile

object DarwinFilterPackage {
    data class Filter(val title: String, val fileName: String)
    data class Impulse(val samples: FloatArray, val crc: Int)

    fun list(file: File): List<Filter> = ZipFile(file).use(::list)

    private fun list(zip: ZipFile): List<Filter> {
        val manifest = zip.getEntry("filter.json")
            ?: throw IllegalArgumentException("Missing filter.json")
        require(!manifest.isDirectory && manifest.size in 1..MAX_MANIFEST_BYTES)
        val manifestBytes = zip.getInputStream(manifest).use { it.readBounded(MAX_MANIFEST_BYTES.toInt()) }
        val root = JsonParser.parseString(manifestBytes.toString(Charsets.UTF_8)).asJsonObject
        return root.getAsJsonArray("list")?.map { item ->
            val value = item.asJsonObject
            val title = value.get("title")?.asString?.trim().orEmpty()
            val path = value.get("path")?.asString.orEmpty()
            val fileName = path.substringAfterLast('/')
            require(title.isNotEmpty() && title.length <= MAX_LABEL_LENGTH && fileName.endsWith(".flt", true))
            require(
                (path == fileName || path == HIBY_FILTER_PREFIX + fileName) &&
                    '\\' !in path && ':' !in path && fileName == File(fileName).name
            )
            val entry = zip.getEntry(fileName) ?: throw IllegalArgumentException("Missing $fileName")
            require(!entry.isDirectory && entry.size == FILTER_BYTES.toLong())
            Filter(title, fileName)
        }?.also { require(it.isNotEmpty()) } ?: throw IllegalArgumentException("Missing filter list")
    }

    fun read(file: File, selectedFile: String): Impulse = ZipFile(file).use { zip ->
        val filters = list(zip)
        val filter = filters.firstOrNull { it.fileName == selectedFile } ?: filters.first()
        val bytes = zip.getInputStream(zip.getEntry(filter.fileName)).use { it.readBounded(FILTER_BYTES) }
        require(bytes.size == FILTER_BYTES)

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val coefficients = DoubleArray(FILTER_TAPS)
        var sum = 0.0
        var compensation = 0.0
        var magnitude = 0.0
        for (index in coefficients.indices) {
            val value = buffer.int / Q31_SCALE
            coefficients[index] = value
            val corrected = value - compensation
            val next = sum + corrected
            compensation = (next - sum) - corrected
            sum = next
            magnitude += kotlin.math.abs(value)
        }
        require(sum.isFinite() && magnitude.isFinite() && magnitude > 0.0)
        require(kotlin.math.abs(sum) >= magnitude * MIN_DC_SUM_RATIO)
        val samples = FloatArray(FILTER_TAPS) { (coefficients[it] / sum).toFloat() }
        require(samples.all(Float::isFinite))

        val crc = CRC32().apply { update(bytes) }.value.toInt()
        Impulse(samples, crc)
    }

    private fun InputStream.readBounded(maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(maxBytes, 8192))
        val buffer = ByteArray(minOf(maxBytes + 1, 8192))
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            require(count > 0 && total + count <= maxBytes)
            output.write(buffer, 0, count)
            total += count
        }
        return output.toByteArray()
    }

    private const val FILTER_TAPS = 256
    private const val FILTER_BYTES = FILTER_TAPS * Int.SIZE_BYTES
    private const val Q31_SCALE = 2147483648.0
    private const val MIN_DC_SUM_RATIO = 0.01
    private const val MAX_MANIFEST_BYTES = 64 * 1024L
    private const val MAX_LABEL_LENGTH = 128
    @SuppressLint("SdCardPath") // This is a package-manifest identifier, not a local filesystem path.
    private const val HIBY_FILTER_PREFIX = "/mnt/sdcard/filter/"
}

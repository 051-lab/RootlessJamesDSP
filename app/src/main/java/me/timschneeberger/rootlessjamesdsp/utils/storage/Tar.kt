package me.timschneeberger.rootlessjamesdsp.utils.storage

import android.content.Context
import org.kamranzafar.jtar.TarEntry
import org.kamranzafar.jtar.TarHeader
import org.kamranzafar.jtar.TarInputStream
import org.kamranzafar.jtar.TarOutputStream
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

object Tar {
    private const val FILE_METADATA = "metadata"

    /**
     * Create tar composer
     * @throws FileNotFoundException if file already exists as a directory or cannot be created for other reasons
     * @throws SecurityException if write access is denied
     */
    class Composer: AutoCloseable, KoinComponent {
        constructor(outputStream: OutputStream) {
            stream = TarOutputStream(outputStream)
        }

        constructor(file: File) {
            stream = TarOutputStream(file)
        }

        private val context: Context by inject()
        private val stream: TarOutputStream

        var metadata = mutableMapOf<String, String>()

        fun add(file: File, entryPath: String? = null): Boolean {
            if (!file.exists() || file.isDirectory) {
                Timber.e("addFile: ${file.absolutePath} is not valid")
                return false
            }

            stream.putNextEntry(TarEntry(file, (entryPath ?: file.name)))
            BufferedInputStream(FileInputStream(file)).use { origin ->
                var count: Int
                val data = ByteArray(2048)
                while (origin.read(data).also { count = it } != -1) {
                    stream.write(data, 0, count)
                }
                stream.flush()
            }
            return true
        }

        override fun close() {
            add(
                File(context.cacheDir, FILE_METADATA).apply {
                    writeText(
                        metadata
                            .map { "${it.key}=${it.value}" }
                            .joinToString("\n")
                    )
                }
            )
            stream.close()
        }
    }

    /** Create tar reader */
    class Reader(
        private val inStream: InputStream,
        private val shouldExtract: ((entryName: String) -> Boolean) = { true }
    ) {
        private fun process(onNextEntry: (tis: TarInputStream, entry: TarEntry) -> Unit) {
            TarInputStream(BufferedInputStream(inStream)).use { tis ->
                var entry: TarEntry?
                var entryCount = 0
                var expandedBytes = 0L
                val names = mutableSetOf<String>()
                while (tis.nextEntry.also { entry = it } != null) {
                    val current = entry ?: break
                    val entryName = current.name
                    if (++entryCount > MAX_ENTRIES || !isSafeEntryName(entryName) || !names.add(entryName))
                        throw IOException("Unsafe or excessive archive entries")
                    if (!current.isDirectory && current.header.linkFlag != TarHeader.LF_NORMAL &&
                        current.header.linkFlag != TarHeader.LF_OLDNORM)
                        throw IOException("Archive links are not supported")
                    if (current.size < 0 || current.size > MAX_EXPANDED_BYTES - expandedBytes)
                        throw IOException("Archive is too large")
                    expandedBytes += current.size

                    if (!shouldExtract(entryName) && entryName != FILE_METADATA) {
                        Timber.w("Entry name ignored: $entryName")
                        continue
                    }

                    onNextEntry(tis, current)
                }
            }
        }

        fun validate(): Boolean {
            Timber.d("Validating preset")

            var knownCount = 0
            try {
                process { _, entry ->
                    if (!entry.isDirectory && entry.name != FILE_METADATA)
                        knownCount++
                }
            }
            catch(ex: Exception) {
                Timber.e("Validation failed due to exception")
                Timber.w(ex)
                return false
            }

            if (knownCount < 1) {
                Timber.e("Archive did not contain any useful data")
                return false
            }

            return true
        }

        fun extract(targetFolder: File) : Map<String, String>? {
            if (targetFolder.exists() && !targetFolder.deleteRecursively())
                return null
            if (!targetFolder.mkdirs() && !targetFolder.isDirectory)
                return null

            val metadataBytes = ByteArrayOutputStream()
            val root = targetFolder.canonicalFile
            var extractedBytes = 0L
            try {
                process { stream, entry ->
                    val target = File(root, entry.name).canonicalFile
                    if (!target.path.startsWith(root.path + File.separator))
                        throw IOException("Archive entry escaped extraction root")

                    if (entry.isDirectory) {
                        if (!target.mkdirs() && !target.isDirectory)
                            throw IOException("Unable to create archive directory")
                    } else {
                        if (entry.name != FILE_METADATA &&
                            target.parentFile?.let { !it.mkdirs() && !it.isDirectory } == true)
                            throw IOException("Unable to create archive directory")

                        val destination: OutputStream = if (entry.name == FILE_METADATA)
                            metadataBytes
                        else
                            BufferedOutputStream(FileOutputStream(target))
                        destination.use { dest ->
                            var entryBytes = 0L
                            val data = ByteArray(8192)
                            while (true) {
                                val count = stream.read(data)
                                if (count == -1) break
                                entryBytes += count
                                extractedBytes += count
                                if (extractedBytes > MAX_EXPANDED_BYTES ||
                                    entry.name == FILE_METADATA && entryBytes > MAX_METADATA_BYTES)
                                    throw IOException("Archive is too large")
                                dest.write(data, 0, count)
                            }
                            if (entryBytes != entry.size)
                                throw IOException("Truncated archive entry")
                            dest.flush()
                        }
                    }
                }
                metadataBytes.flush()
            }
            catch(ex: Exception) {
                Timber.e("Extraction failed")
                Timber.w(ex)
                targetFolder.deleteRecursively()
                return null
            }

            return mutableMapOf<String, String>().apply {
                metadataBytes.toString(Charsets.UTF_8.name()).lines().forEach {
                    val args = it.split("=", limit = 2)
                    if(args.size < 2)
                        return@forEach

                    this[args[0]] = args[1].trim()
                }
            }
        }

        private fun isSafeEntryName(name: String): Boolean =
            name.isNotBlank() && name.length <= MAX_ENTRY_NAME_LENGTH &&
                !File(name).isAbsolute && '/' != name.first() && '\\' !in name && '\u0000' !in name &&
                name.split('/').none { it.isEmpty() || it == "." || it == ".." }
    }

    private const val MAX_ENTRIES = 4096
    private const val MAX_ENTRY_NAME_LENGTH = 512
    private const val MAX_METADATA_BYTES = 64 * 1024L
    private const val MAX_EXPANDED_BYTES = 1024L * 1024L * 1024L

}

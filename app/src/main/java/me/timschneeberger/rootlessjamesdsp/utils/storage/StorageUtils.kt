package me.timschneeberger.rootlessjamesdsp.utils.storage

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import timber.log.Timber
import java.io.File
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption


object StorageUtils {

    fun importFile(
        context: Context,
        targetDir: String,
        uri: Uri,
        validator: ((InputStream) -> Boolean)? = null,
    ): File? {
        val name = queryName(context, uri)
        if (name.isNullOrBlank() || '/' in name || '\\' in name || '\u0000' in name || name == "." || name == "..") {
            Timber.e("importFile: unsafe or missing display name")
            return null
        }

        val directory = File(targetDir).canonicalFile
        if (!directory.mkdirs() && !directory.isDirectory)
            return null
        val destinationFile = File(directory, name).canonicalFile
        if (destinationFile.parentFile != directory)
            return null
        val temporary = try {
            File.createTempFile(".import-", ".tmp", directory)
        } catch (ex: Exception) {
            Timber.e(ex, "Failed to create import staging file")
            return null
        }
        try {
            val input = context.contentResolver.openInputStream(uri) ?: return null
            input.use { ins -> temporary.outputStream().use { out -> ins.copyTo(out) } }
            if (validator != null && !temporary.inputStream().buffered().use(validator))
                return null
            try {
                Files.move(
                    temporary.toPath(), destinationFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), destinationFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (ex: Exception) {
            Timber.e(ex, "Failed to import file")
            return null
        } finally {
            temporary.delete()
        }
        return destinationFile
    }

    fun openInputStreamSafe(context: Context, uri: Uri): InputStream? {
        return try {
            context.contentResolver.openInputStream(uri)
        } catch (ex: Exception) {
            Timber.e(ex, "Failed to open input stream")
            null
        }
    }

    fun queryName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex < 0 || !cursor.moveToFirst() || cursor.isNull(nameIndex)) null
                else cursor.getString(nameIndex)
            }
        } catch (ex: Exception) {
            Timber.e(ex, "Failed to query file name")
            null
        }
    }
}

package me.timschneeberger.rootlessjamesdsp.session.dump.utils

import android.content.Context
import android.os.ParcelFileDescriptor
import me.timschneeberger.rootlessjamesdsp.utils.extensions.PermissionExtensions.hasDumpPermission
import rikka.shizuku.SystemServiceHelper
import timber.log.Timber
import java.io.IOException

object DumpUtils {
    fun dumpLines(context: Context, service: String, args: Array<String> = emptyArray()): List<String>? {
        return dumpAll(context, service, args)?.lines()
    }

    fun dumpAll(context: Context, service: String, args: Array<String> = emptyArray()): String? {
        if(!context.hasDumpPermission())
            return null

        try {
            val serviceBinder = SystemServiceHelper.getSystemService(service)
            if (serviceBinder == null) {
                Timber.wtf("Service '$service' does not exist")
                return null
            }

            val pipe = ParcelFileDescriptor.createPipe()
            val readPipe = pipe[0]
            val writePipe = pipe[1]
            return ParcelFileDescriptor.AutoCloseInputStream(readPipe)
                .bufferedReader(Charsets.UTF_8)
                .use { reader ->
                    writePipe.use { serviceBinder.dumpAsync(it.fileDescriptor, args) }
                    reader.readText()
                }
        }
        catch (ex: IOException)
        {
            Timber.e("IOException during dump")
            Timber.d(ex)
            return null
        }
        catch (ex: Exception)
        {
            Timber.wtf(ex)
            return null
        }
    }
}

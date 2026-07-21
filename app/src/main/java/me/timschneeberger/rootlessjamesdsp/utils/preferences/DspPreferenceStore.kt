package me.timschneeberger.rootlessjamesdsp.utils.preferences

import android.content.Context
import android.content.SharedPreferences
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.IOException

internal object DspPreferenceStore {
    fun restore(context: Context, directory: File, clearExisting: Boolean) {
        val restored = readDirectory(directory)
        if (clearExisting)
            clear(context)

        restored.forEach { (namespace, values) ->
            val editor = getPreferences(context, namespace).edit()
            values.forEach { (key, value) -> editor.putValue(key, value) }
            if (!editor.commit())
                throw IOException("Failed to restore DSP preferences for $namespace")
        }
    }

    fun clear(context: Context) {
        preferenceDirectory(context).listFiles(::isDspPreferenceFile)
            .orEmpty()
            .forEach { file ->
                if (!getPreferences(context, file.nameWithoutExtension).edit().clear().commit())
                    throw IOException("Failed to clear DSP preferences for ${file.nameWithoutExtension}")
            }
    }

    internal fun read(file: File): Map<String, Any> {
        val parser = Xml.newPullParser()
        return file.inputStream().buffered().use { input ->
            parser.setInput(input, Charsets.UTF_8.name())
            readMap(parser)
        }
    }

    private fun readDirectory(directory: File): Map<String, Map<String, Any>> =
        directory.listFiles(::isDspPreferenceFile)
            .orEmpty()
            .sortedBy(File::getName)
            .associate { it.nameWithoutExtension to read(it) }

    private fun readMap(parser: XmlPullParser): Map<String, Any> {
        val values = linkedMapOf<String, Any>()
        var rootSeen = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.DOCDECL -> throw IOException("Preference XML declarations are not allowed")
                XmlPullParser.START_TAG -> when (parser.depth) {
                    1 -> {
                        if (rootSeen || parser.name != "map")
                            throw IOException("Invalid preference XML root")
                        rootSeen = true
                    }
                    2 -> {
                        if (!rootSeen)
                            throw IOException("Preference entry precedes map root")
                        val key = parser.getAttributeValue(null, "name")
                            ?.takeIf(String::isNotEmpty)
                            ?: throw IOException("Preference entry has no name")
                        values[key] = readValue(parser)
                    }
                    else -> throw IOException("Unexpected nested preference element")
                }
                XmlPullParser.TEXT -> if (!parser.text.isNullOrBlank())
                    throw IOException("Unexpected preference XML text")
            }
            event = parser.next()
        }
        if (!rootSeen)
            throw IOException("Preference XML has no map root")
        return values
    }

    private fun readValue(parser: XmlPullParser): Any = when (parser.name) {
        "string" -> parser.nextText()
        "int" -> valueAttribute(parser).toIntOrNull()
            ?: throw IOException("Invalid integer preference")
        "long" -> valueAttribute(parser).toLongOrNull()
            ?: throw IOException("Invalid long preference")
        "float" -> valueAttribute(parser).toFloatOrNull()
            ?: throw IOException("Invalid float preference")
        "boolean" -> valueAttribute(parser).toBooleanStrictOrNull()
            ?: throw IOException("Invalid boolean preference")
        "set" -> readStringSet(parser)
        else -> throw IOException("Unsupported preference type ${parser.name}")
    }

    private fun readStringSet(parser: XmlPullParser): Set<String> {
        val depth = parser.depth
        val values = linkedSetOf<String>()
        while (true) {
            when (parser.next()) {
                XmlPullParser.DOCDECL -> throw IOException("Preference XML declarations are not allowed")
                XmlPullParser.START_TAG -> {
                    if (parser.depth != depth + 1 || parser.name != "string")
                        throw IOException("Invalid string-set preference")
                    values.add(parser.nextText())
                }
                XmlPullParser.END_TAG -> if (parser.depth == depth)
                    return values
                XmlPullParser.TEXT -> if (!parser.text.isNullOrBlank())
                    throw IOException("Unexpected string-set text")
                XmlPullParser.END_DOCUMENT -> throw IOException("Truncated string-set preference")
            }
        }
    }

    private fun valueAttribute(parser: XmlPullParser): String =
        parser.getAttributeValue(null, "value")
            ?: throw IOException("Preference value is missing")

    private fun SharedPreferences.Editor.putValue(key: String, value: Any) {
        when (value) {
            is Boolean -> putBoolean(key, value)
            is Float -> putFloat(key, value)
            is Int -> putInt(key, value)
            is Long -> putLong(key, value)
            is String -> putString(key, value)
            is Set<*> -> putStringSet(key, value.map {
                it as? String ?: throw IOException("Invalid string-set value")
            }.toSet())
            else -> throw IOException("Unsupported preference value")
        }
    }

    private fun preferenceDirectory(context: Context): File =
        File(context.applicationInfo.dataDir, "shared_prefs")

    private fun isDspPreferenceFile(file: File): Boolean =
        file.isFile && file.name.startsWith("dsp_") && file.extension == "xml"

    @Suppress("DEPRECATION")
    private fun getPreferences(context: Context, namespace: String): SharedPreferences =
        context.getSharedPreferences(namespace, Context.MODE_MULTI_PROCESS)
}

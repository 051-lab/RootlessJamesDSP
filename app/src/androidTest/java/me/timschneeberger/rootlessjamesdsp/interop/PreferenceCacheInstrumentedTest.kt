package me.timschneeberger.rootlessjamesdsp.interop

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PreferenceCacheInstrumentedTest {
    @Test
    fun commitsOnlySuccessfulNamespaces() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val cache = PreferenceCache(context)

        cache.markChangesAsPending(listOf("successful", "failed", "failed"))
        cache.markChangesAsCommitted(listOf("successful"))

        assertEquals(listOf("failed"), cache.changedNamespaces)
    }

    @Test
    fun seedsBundledDarwinForAnUnconfiguredDeviceProfile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
            .createDeviceProtectedStorageContext()
        val archive = File(context.getExternalFilesDir(null), PreferenceCache.BUNDLED_DARWIN_PATH)
        val archiveExisted = archive.isFile
        val darwin = PreferenceCache.getPreferences(context, Constants.PREF_DARWIN)
        val convolver = PreferenceCache.getPreferences(context, Constants.PREF_CONVOLVER)
        val fileKey = context.getString(R.string.key_darwin_file)
        val filterKey = context.getString(R.string.key_darwin_filter)
        val enableKey = context.getString(R.string.key_darwin_enable)
        val convolverKey = context.getString(R.string.key_convolver_enable)

        try {
            archive.parentFile?.mkdirs()
            if (!archiveExisted) archive.writeBytes(byteArrayOf())
            darwin.edit().clear()
                .putString(fileKey, "")
                .putString(filterKey, "")
                .putBoolean(enableKey, false)
                .commit()
            convolver.edit().clear().putBoolean(convolverKey, true).commit()

            PreferenceCache.applyBundledDarwinDefaults(context)

            assertEquals(PreferenceCache.BUNDLED_DARWIN_PATH, darwin.getString(fileKey, null))
            assertEquals(PreferenceCache.BUNDLED_DARWIN_FILTER, darwin.getString(filterKey, null))
            assertTrue(darwin.getBoolean(enableKey, false))
            assertFalse(convolver.getBoolean(convolverKey, true))
        } finally {
            darwin.edit().clear().commit()
            convolver.edit().clear().commit()
            if (!archiveExisted) archive.delete()
        }
    }
}

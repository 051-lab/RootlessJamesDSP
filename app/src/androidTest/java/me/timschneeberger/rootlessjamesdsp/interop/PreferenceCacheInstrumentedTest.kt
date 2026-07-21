package me.timschneeberger.rootlessjamesdsp.interop

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

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
}

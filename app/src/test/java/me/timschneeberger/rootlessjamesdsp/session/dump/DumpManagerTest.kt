package me.timschneeberger.rootlessjamesdsp.session.dump

import org.junit.Assert.assertEquals
import org.junit.Test

class DumpManagerTest {
    @Test
    fun invalidPreferenceFallsBackToAudioPolicy() {
        assertEquals(DumpManager.Method.AudioPolicyService, DumpManager.Method.fromInt(Int.MAX_VALUE))
    }
}

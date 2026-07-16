package me.timschneeberger.rootlessjamesdsp

import me.timschneeberger.rootlessjamesdsp.model.preference.AudioEncoding
import org.junit.Assert.assertEquals
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun invalidAudioEncodingFallsBackToFloat() {
        assertEquals(AudioEncoding.PcmFloat, AudioEncoding.fromInt(999))
    }
}

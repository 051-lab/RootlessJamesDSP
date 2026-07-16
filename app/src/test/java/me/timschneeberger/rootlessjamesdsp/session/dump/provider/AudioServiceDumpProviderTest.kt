package me.timschneeberger.rootlessjamesdsp.session.dump.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AudioServiceDumpProviderTest {
    @Test
    fun aAudioSessionFallsBackToAudioFlingerSessionId() {
        val row = "AudioPlaybackConfiguration u/pid:10267/9464 " +
            "usage=USAGE_MEDIA content=CONTENT_TYPE_MUSIC sessionId:-1"
        val match = PLAYBACK_CONFIG_REGEX_API_31.find(row)

        assertNotNull(match)
        assertEquals(
            listOf(229609, 229610),
            resolveAudioSessionIds(
                match?.groups?.get(5)?.value,
                9464,
                mapOf(9464 to listOf(229609, 229610))
            )
        )
    }

    @Test
    fun reportedSessionIdTakesPrecedence() {
        assertEquals(
            listOf(42),
            resolveAudioSessionIds("42", 9464, mapOf(9464 to listOf(1, 2)))
        )
    }
}

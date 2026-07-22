package me.timschneeberger.rootlessjamesdsp.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class DarwinRequestGateTest {
    @Test
    fun commitsDisabledToEnabled() {
        val disabled = Config("disabled", false)
        val enabled = Config("enabled", true)
        val gate = DarwinRequestGate(disabled)
        var active = disabled

        gate.request(enabled)

        assertTrue(gate.runIfCurrent(enabled) { active = enabled })
        assertEquals(enabled, active)
    }

    @Test
    fun commitsEnabledReplacementAndRejectsSupersededBuild() {
        val first = Config("first", true)
        val replacement = Config("replacement", true)
        val gate = DarwinRequestGate(first)
        var active = first

        gate.request(replacement)

        assertFalse(gate.runIfCurrent(first) { active = first })
        assertTrue(gate.runIfCurrent(replacement) { active = replacement })
        assertEquals(replacement, active)
    }

    @Test
    fun commitsEnabledToDisabled() {
        val enabled = Config("enabled", true)
        val disabled = Config("disabled", false)
        val gate = DarwinRequestGate(enabled)
        var active = enabled

        gate.request(disabled)

        assertTrue(gate.runIfCurrent(disabled) { active = disabled })
        assertEquals(disabled, active)
    }

    @Test
    fun staleBuildCannotPublishAfterNewerRequest() {
        val stale = Config("stale", true)
        val latest = Config("latest", true)
        val gate = DarwinRequestGate<Config>()
        var published: Config? = null

        gate.request(stale)
        gate.request(latest)

        assertFalse(gate.runIfCurrent(stale) { published = stale })
        assertTrue(gate.runIfCurrent(latest) { published = latest })
        assertEquals(latest, published)
    }

    @Test
    fun invalidReplacementRestoresActiveRequestAndAllowsRetry() {
        val active = Config("active", true)
        val invalid = Config("invalid", true)
        val retry = Config("retry", true)
        val gate = DarwinRequestGate(active)
        var committed = active

        gate.request(invalid)

        assertTrue(gate.restoreIfCurrent(invalid, active))
        assertTrue(gate.isCurrent(active))
        assertFalse(gate.runIfCurrent(invalid) { committed = invalid })

        gate.request(retry)
        assertTrue(gate.runIfCurrent(retry) { committed = retry })
        assertEquals(retry, committed)
    }

    @Test
    fun newerRequestCannotOvertakeCurrentCommit() {
        val first = Config("first", true)
        val latest = Config("latest", true)
        val gate = DarwinRequestGate(first)
        val commitEntered = CountDownLatch(1)
        val releaseCommit = CountDownLatch(1)
        val commitFinished = CountDownLatch(1)
        val requestStarted = CountDownLatch(1)
        val requestFinished = CountDownLatch(1)
        val commitSucceeded = AtomicBoolean(false)

        val commitThread = thread {
            commitSucceeded.set(gate.runIfCurrent(first) {
                commitEntered.countDown()
                releaseCommit.await(5, TimeUnit.SECONDS)
            })
            commitFinished.countDown()
        }
        assertTrue(commitEntered.await(5, TimeUnit.SECONDS))

        val requestThread = thread {
            requestStarted.countDown()
            gate.request(latest)
            requestFinished.countDown()
        }
        assertTrue(requestStarted.await(5, TimeUnit.SECONDS))
        assertFalse(requestFinished.await(100, TimeUnit.MILLISECONDS))

        releaseCommit.countDown()
        assertTrue(commitFinished.await(5, TimeUnit.SECONDS))
        assertTrue(requestFinished.await(5, TimeUnit.SECONDS))
        commitThread.join()
        requestThread.join()

        assertTrue(commitSucceeded.get())
        assertTrue(gate.isCurrent(latest))
    }

    private data class Config(val name: String, val enabled: Boolean)
}

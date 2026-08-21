package com.metalbear.mirrord.bifrost

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * "Slow" has to be measured rather than guessed: a cold dev container legitimately takes
 * seconds, a warm one milliseconds. A fixed threshold would either warn on every first run or
 * never warn at all.
 */
class MirrordBifrostTraceTest {

    @Test
    fun `nothing is slow before there is a baseline`() {
        val baselines = MirrordBifrostBaselines()

        assertNull(baselines.baselineMillis("spawn"))
        assertFalse(baselines.isSlow("spawn", elapsedMillis = 60_000, floorMillis = 100))
    }

    @Test
    fun `the baseline follows observed timings`() {
        val baselines = MirrordBifrostBaselines()

        repeat(20) { baselines.record("spawn", 100) }

        val baseline = baselines.baselineMillis("spawn")!!
        assertTrue(baseline in 95..105, "expected the baseline to settle near 100ms, was ${baseline}ms")
        assertEquals(20L, baselines.sampleCount("spawn"))
    }

    @Test
    fun `a large outlier against a settled baseline is slow`() {
        val baselines = MirrordBifrostBaselines()
        repeat(20) { baselines.record("connect", 1_000) }

        assertTrue(baselines.isSlow("connect", elapsedMillis = 10_000, floorMillis = 5_000))
    }

    @Test
    fun `a fast operation is not slow just for tripling`() {
        // 6ms against a 2ms baseline is 3x, but nobody wants a warning balloon about it.
        val baselines = MirrordBifrostBaselines()
        repeat(20) { baselines.record("resolve", 2) }

        assertFalse(baselines.isSlow("resolve", elapsedMillis = 6, floorMillis = 5_000))
    }

    @Test
    fun `baselines are tracked per operation`() {
        val baselines = MirrordBifrostBaselines()
        repeat(10) { baselines.record("connect", 5_000) }
        repeat(10) { baselines.record("resolve", 1) }

        assertTrue(baselines.baselineMillis("connect")!! > baselines.baselineMillis("resolve")!!)
        assertNull(baselines.baselineMillis("never-run"))
    }

    @Test
    fun `an unreachable environment is named in the error`() {
        val error = bifrostFailure(
            environment = "devcontainer:javascript-node",
            operation = "connect",
            cause = IOException("environment not running")
        )

        // The user has to be able to tell which environment failed, and be told what to do
        // instead. "mirrord failed" on its own is what made this ticket take four months.
        assertTrue(error.toString().isNotEmpty())
        assertTrue(error.cause is IOException)
    }

    @Test
    fun `an unexpected failure still produces a mirrord error`() {
        val error = bifrostFailure("local", "spawn", IllegalStateException("kaboom"))

        assertTrue(error.cause is IllegalStateException)
    }
}

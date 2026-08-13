package com.metalbear.mirrord.bifrost

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.runBlockingMaybeCancellable
import com.metalbear.mirrord.MirrordError
import com.metalbear.mirrord.MirrordLogger
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Running per-operation timings, so "slow" is measured rather than guessed.
 *
 * Reaching a cold dev container legitimately takes seconds; reaching a warm one takes
 * milliseconds. A fixed threshold would either cry wolf on every first run or never fire at all.
 * An exponentially weighted moving average adapts to whatever normal turns out to be on a given
 * machine, which is what makes an intermittent stall visible as an outlier instead of noise.
 */
class MirrordBifrostBaselines {
    private val baselines = ConcurrentHashMap<String, Double>()
    private val counts = ConcurrentHashMap<String, Long>()

    fun record(operation: String, elapsedMillis: Long) {
        baselines.compute(operation) { _, previous ->
            if (previous == null) elapsedMillis.toDouble() else (SMOOTHING * elapsedMillis) + ((1 - SMOOTHING) * previous)
        }
        counts.merge(operation, 1L, Long::plus)
    }

    fun baselineMillis(operation: String): Long? = baselines[operation]?.toLong()

    fun sampleCount(operation: String): Long = counts[operation] ?: 0L

    /**
     * An operation is slow when it exceeds both [floorMillis] and a multiple of its own
     * baseline. The floor stops a fast operation from being called slow just because it was
     * three times its usual two milliseconds.
     */
    fun isSlow(operation: String, elapsedMillis: Long, floorMillis: Long): Boolean {
        if (elapsedMillis < floorMillis) return false
        val baseline = baselineMillis(operation) ?: return false
        return elapsedMillis > baseline * SLOW_FACTOR
    }

    private companion object {
        const val SMOOTHING = 0.25
        const val SLOW_FACTOR = 3
    }
}

/**
 * Wraps every crossing of the bridge so that a slow or failed one is obvious in `idea.log`.
 *
 * COR-1385 went four months partly because the plugin failed *silently*: no error, no warning,
 * just a connected-layer count that stayed at zero. Remote environments add two more ways to
 * fail quietly — slow, and unreachable — so each crossing reports which of the three it was.
 *
 * Grep `mirrord.bifrost:` in `idea.log` and the whole story should be there without a debugger.
 */
class MirrordBifrostTracer(
    private val softMillis: Long = DEFAULT_SOFT_MILLIS,
    private val hardMillis: Long = DEFAULT_HARD_MILLIS,
    private val onSlow: (String) -> Unit = {},
    private val baselines: MirrordBifrostBaselines = MirrordBifrostBaselines()
) {
    private val nextId = AtomicLong()

    /**
     * Runs one crossing, bounded and instrumented.
     *
     * Uses `runBlockingMaybeCancellable` rather than EEL's own `*Blocking` bridges: those are
     * uncancellable, so a wedged container would hold the thread until some socket timeout gave
     * up, and the Cancel button the surrounding [com.intellij.openapi.progress.ProgressManager]
     * already shows would do nothing. This way the platform can cancel us, and [withTimeout]
     * bounds us even when there is no progress indicator to cancel from.
     */
    fun <T> crossing(operation: String, environment: String, block: suspend () -> T): T {
        if (ApplicationManager.getApplication()?.isDispatchThread == true) {
            // Loud in development, logged in production. Blocking the EDT on a container that is
            // starting up freezes the whole IDE, and it is the easiest mistake to make here.
            MirrordLogger.logger.error(
                "mirrord.bifrost: crossing '$operation' attempted on the EDT — this can freeze the IDE"
            )
        }

        val id = "bifrost-%04d".format(nextId.incrementAndGet())
        val baseline = baselines.baselineMillis(operation)
        MirrordLogger.logger.info(
            "mirrord.bifrost: BEGIN   op=$operation id=$id env=$environment " +
                "baseline=${baseline?.let { "${it}ms" } ?: "none"} n=${baselines.sampleCount(operation)} hard=${hardMillis}ms"
        )

        val started = System.nanoTime()
        fun elapsed() = (System.nanoTime() - started) / 1_000_000

        return try {
            val result = runBlockingMaybeCancellable {
                withTimeout(hardMillis) { block() }
            }
            val took = elapsed()
            baselines.record(operation, took)

            if (baselines.isSlow(operation, took, softMillis)) {
                val message = "mirrord: $operation took ${took}ms in $environment (usually ${baseline}ms)"
                MirrordLogger.logger.warn("mirrord.bifrost: SLOW    op=$operation id=$id env=$environment elapsed=${took}ms baseline=${baseline}ms")
                onSlow(message)
            } else {
                MirrordLogger.logger.info("mirrord.bifrost: OK      op=$operation id=$id env=$environment elapsed=${took}ms")
            }
            result
        } catch (e: TimeoutCancellationException) {
            MirrordLogger.logger.warn("mirrord.bifrost: TIMEOUT op=$operation id=$id env=$environment elapsed=${elapsed()}ms limit=${hardMillis}ms", e)
            throw bifrostFailure(environment, operation, e)
        } catch (e: IOException) {
            // Covers the platform's own "environment not reachable" signal without naming it:
            // EelUnavailableException is an IOException, and it exists in build 261 but not 262.
            // Referencing it directly made the plugin fail to load on newer IDEs entirely.
            MirrordLogger.logger.warn("mirrord.bifrost: UNAVAIL op=$operation id=$id env=$environment elapsed=${elapsed()}ms", e)
            throw bifrostFailure(environment, operation, e)
        } catch (e: MirrordError) {
            // Already carries a user-facing message; do not wrap it in a second one.
            MirrordLogger.logger.warn("mirrord.bifrost: FAIL    op=$operation id=$id env=$environment elapsed=${elapsed()}ms: ${e.cause?.message ?: e.message}")
            throw e
        } catch (e: Throwable) {
            MirrordLogger.logger.warn("mirrord.bifrost: FAIL    op=$operation id=$id env=$environment elapsed=${elapsed()}ms: ${e.message}", e)
            throw bifrostFailure(environment, operation, e)
        }
    }

    companion object {
        /** Reaching a container for the first time can mean starting and deploying an agent. */
        const val DEFAULT_SOFT_MILLIS = 5_000L
        const val DEFAULT_HARD_MILLIS = 60_000L

        val shared = MirrordBifrostTracer()
    }
}

/**
 * Turns a failed crossing into an error that names the environment and offers a way forward.
 *
 * Pure, so the message wording is unit-tested.
 */
internal fun bifrostFailure(environment: String, operation: String, cause: Throwable): MirrordError = when (cause) {
    is TimeoutCancellationException -> MirrordError(
        "mirrord timed out reaching $environment while trying to $operation.",
        CLI_FALLBACK_HELP,
        cause
    )

    is IOException -> MirrordError(
        "mirrord could not reach $environment.",
        CLI_FALLBACK_HELP,
        cause
    )

    else -> MirrordError(
        "mirrord failed to $operation in $environment: ${cause.message ?: cause::class.java.simpleName}",
        CLI_FALLBACK_HELP,
        cause
    )
}

private const val CLI_FALLBACK_HELP =
    "If the environment is a dev container, check that it is running. You can always run mirrord " +
        "directly inside it instead: mirrord exec -f ./mirrord.json -- <your command>"

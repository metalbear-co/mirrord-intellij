package com.metalbear.mirrord.bifrost

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.runBlockingMaybeCancellable
import com.intellij.platform.ide.progress.ModalTaskOwner
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.metalbear.mirrord.MirrordError
import com.metalbear.mirrord.MirrordLogger
import kotlinx.coroutines.CancellationException
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
        // Some platform extension points are called on the EDT by contract and cannot be moved
        // off it -- ExecutionListener.processStarting is one, and that is where the Node/npm path
        // starts mirrord. Still logged as an error, because doing this work on the EDT is a design
        // fault we want to see and fix at the call site, not a state to settle for.
        val onEdt = ApplicationManager.getApplication()?.isDispatchThread == true
        if (onEdt) {
            // WARN, not ERROR. `Logger.error` raises an IDE error report that names mirrord as the
            // plugin to blame, which reads as a crash for a condition the user cannot act on.
            //
            // First occurrence of each operation warns; later ones drop to debug. Silencing them
            // entirely would hide the condition for every product after the first, because the set
            // is per-session and keyed on the operation name rather than on the call site.
            //
            // `runWithModalProgressBlocking` below keeps the IDE responsive, but it does not make
            // the call correct. The fix is to move the work off the EDT at the call site, and this
            // line is how we know whether that has happened.
            val message = "mirrord.bifrost: crossing '$operation' runs on the EDT — the IDE is " +
                "blocked behind a modal dialog until it finishes. This should be moved to a " +
                "background thread; see MirrordNpmExecutionListener.processStarting."

            if (edtReported.add(operation)) {
                MirrordLogger.logger.warn(message)
            } else {
                MirrordLogger.logger.debug(message)
            }
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
            // `runBlockingCancellable` throws IllegalStateException on the EDT in build 262:
            // "This method is forbidden on EDT because it does not pump the event queue." That
            // killed every Node launch. A modal progress dialog is the platform's own prescribed
            // answer -- it pumps the queue, keeps the IDE responsive, and gives the user a Cancel
            // button instead of a frozen window while a cold container starts.
            val result = if (onEdt) {
                runWithModalProgressBlocking(ModalTaskOwner.guess(), "mirrord: $operation in $environment") {
                    withTimeout(hardMillis) { block() }
                }
            } else {
                runBlockingMaybeCancellable {
                    withTimeout(hardMillis) { block() }
                }
            }
            val took = elapsed()

            // Compare before recording. `record` folds this sample into the moving average, so
            // asking `isSlow` afterwards compares the sample against a baseline that has already
            // absorbed it: with SMOOTHING 0.25 and SLOW_FACTOR 3 that turns the documented 3x
            // threshold into an effective 9x, and ordinary outliers stop being reported.
            val slow = baselines.isSlow(operation, took, softMillis)
            baselines.record(operation, took)

            if (slow) {
                val message = "mirrord: $operation took ${took}ms in $environment (usually ${baseline}ms)"
                MirrordLogger.logger.warn("mirrord.bifrost: SLOW    op=$operation id=$id env=$environment elapsed=${took}ms baseline=${baseline}ms")
                // TODO(COR-1385): surface this to the user as a notification, with
                // `withDontShowAgain`. Until then a slow crossing is visible only in the log.
                onSlow(message)
            } else {
                MirrordLogger.logger.info("mirrord.bifrost: OK      op=$operation id=$id env=$environment elapsed=${took}ms")
            }
            result
        } catch (e: TimeoutCancellationException) {
            MirrordLogger.logger.warn("mirrord.bifrost: TIMEOUT op=$operation id=$id env=$environment elapsed=${elapsed()}ms limit=${hardMillis}ms", e)
            throw bifrostFailure(environment, operation, e)
        } catch (e: CancellationException) {
            // Cancellation is control flow, not a failure. The resolution chain in
            // MirrordEnvironmentSource follows the same rule.
            //
            // `ProcessCanceledException` extends `CancellationException`, so one arm covers both
            // the platform's Cancel button and coroutine cancellation.
            //
            // Wrapping it in a MirrordError turned Cancel into an error popup, and into an IDE
            // error report naming the plugin as the thing to blame.
            //
            // Declared after TimeoutCancellationException on purpose. That is a subclass, and a
            // timeout genuinely is a failure.
            MirrordLogger.logger.info("mirrord.bifrost: CANCEL  op=$operation id=$id env=$environment elapsed=${elapsed()}ms")
            throw e
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
            // A LinkageError or ClassNotFoundException means a platform API moved between IDE
            // builds. `bifrostFailure` already turns those into the version-mismatch message; the
            // only thing that differs here is the log tag.
            val tag = if (e is LinkageError || e is ClassNotFoundException) "MISMATCH" else "FAIL    "
            MirrordLogger.logger.warn("mirrord.bifrost: $tag op=$operation id=$id env=$environment elapsed=${elapsed()}ms: ${e.message}", e)
            throw bifrostFailure(environment, operation, e)
        }
    }

    companion object {
        /** Operations already reported as running on the EDT, so each is named at most once. */
        private val edtReported: MutableSet<String> = ConcurrentHashMap.newKeySet()

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

    is LinkageError, is ClassNotFoundException -> MirrordError(
        "mirrord is not compatible with this build of the IDE.",
        "This plugin build was compiled against a different IntelliJ Platform version, and an " +
            "API it needs has moved: ${cause.message}. Please update the mirrord plugin. If it is " +
            "already current, report this with your IDE build number — the EEL API it depends on " +
            "is still experimental and changes between releases.",
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

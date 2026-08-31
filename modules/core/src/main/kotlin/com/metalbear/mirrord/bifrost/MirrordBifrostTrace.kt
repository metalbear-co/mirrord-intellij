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
 * A cold dev container takes seconds to reach and a warm one takes milliseconds, so a fixed
 * threshold either fires on every first run or never fires. The moving average adapts to
 * whatever normal is on a given machine.
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
     * An operation is slow when it passes both [floorMillis] and a multiple of its own baseline.
     * The floor stops a fast operation being called slow at three times its usual 2 ms.
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
 * Wraps every crossing into a [MirrordEnvironment], so a slow or failed one shows in `idea.log`.
 *
 * A remote environment fails in three ways — slow, unreachable, and broken — and each crossing
 * reports which one it was.
 *
 * Grep `mirrord.bifrost:` in `idea.log` if you suspect something is wrong.
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
     * Uses `runBlockingMaybeCancellable` rather than EEL's own `*Blocking` bridges, which cannot
     * be cancelled: a wedged container would hold the thread until a socket timeout, and the
     * Cancel button of the surrounding
     * [com.intellij.openapi.progress.ProgressManager] would do nothing.
     *
     * [withTimeout] bounds the call even when there is no progress indicator to cancel from.
     */
    fun <T> crossing(operation: String, environment: String, block: suspend () -> T): T {
        // Some extension points run on the EDT by platform contract and cannot be moved off it.
        // `ExecutionListener.processStarting` is one, and that is where the Node path starts
        // mirrord.
        val onEdt = ApplicationManager.getApplication()?.isDispatchThread == true
        if (onEdt) {
            // WARN, not ERROR: `Logger.error` raises an IDE error report naming mirrord, for a
            // condition the user cannot act on.
            //
            // First occurrence of each operation warns, later ones drop to debug. The modal
            // dialog below keeps the IDE responsive but does not make the call correct.
            val message = "mirrord.bifrost: crossing '$operation' runs on the EDT. " +
                "Move it to a background thread; see MirrordNpmExecutionListener.processStarting."

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
            // EEL COMPAT 261/262: `runBlockingCancellable` became forbidden on the EDT in 262
            // ("does not pump the event queue"), which killed every Node launch. A modal progress
            // dialog is the platform's own answer: it pumps the queue and gives the user a Cancel
            // button while a cold container starts.
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

            // Compare before recording. `record` folds this sample into the average, so asking
            // afterwards raises the effective threshold from 3x to 9x.
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
            // Cancellation is control flow, not a failure: wrapping it in a MirrordError turns
            // Cancel into an error popup. `ProcessCanceledException` extends this, so one arm
            // covers the Cancel button and coroutine cancellation.
            //
            // Declared after `TimeoutCancellationException`, a subclass that *is* a failure.
            MirrordLogger.logger.info("mirrord.bifrost: CANCEL  op=$operation id=$id env=$environment elapsed=${elapsed()}ms")
            throw e
        } catch (e: IOException) {
            // EEL COMPAT 261/262: `EelUnavailableException` exists in 261 and not 262, and
            // naming it made the plugin fail to load on newer IDEs. It is an `IOException`, so
            // this arm catches it without a reference.
            MirrordLogger.logger.warn("mirrord.bifrost: UNAVAIL op=$operation id=$id env=$environment elapsed=${elapsed()}ms", e)
            throw bifrostFailure(environment, operation, e)
        } catch (e: MirrordError) {
            // Already carries a user-facing message; do not wrap it in a second one.
            MirrordLogger.logger.warn("mirrord.bifrost: FAIL    op=$operation id=$id env=$environment elapsed=${elapsed()}ms: ${e.cause?.message ?: e.message}")
            throw e
        } catch (e: Throwable) {
            // EEL COMPAT 261/262: a `LinkageError` or `ClassNotFoundException` means a platform
            // API moved between builds. `bifrostFailure` turns those into the version-mismatch
            // message; only the log tag differs here.
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

/** Turns a failed crossing into an error that names the environment and offers a way forward. */
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

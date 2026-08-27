package com.metalbear.mirrord.utils

import com.intellij.remoterobot.stepsProcessing.StepProcessor

/**
 * Times every `step { }` and prints a table at the end of the run.
 *
 * A failing CI job used to report which step timed out but never how long anything
 * took, so a stuck step and a merely-slow one looked identical.
 *
 * One `StepWorker.registerProcessor` call instruments every step, nested ones
 * included. Output is prefixed `[e2e-timing]` so CI logs can be grepped.
 */
object StepTimings : StepProcessor {

    private data class Entry(val depth: Int, val name: String, val millis: Long, val failed: Boolean)

    private val open = ArrayDeque<Pair<String, Long>>()
    private val done = mutableListOf<Entry>()
    private val failedSteps = mutableSetOf<String>()

    override fun doBeforeStep(stepTitle: String) {
        open.addLast(stepTitle to System.nanoTime())
    }

    override fun doOnSuccess(stepTitle: String) = Unit

    override fun doOnFail(stepTitle: String, e: Throwable) {
        failedSteps += stepTitle
    }

    override fun doAfterStep(stepTitle: String) {
        val started = open.removeLastOrNull() ?: return
        done += Entry(open.size, started.first, (System.nanoTime() - started.second) / 1_000_000, stepTitle in failedSteps)
    }

    /**
     * Remote-robot instruments its own element searches as steps, which buries the
     * test's own steps under hundreds of lines. Those are dropped here, and the count
     * is reported so nothing is hidden silently.
     */
    private fun Entry.isNoise() = name.startsWith("Search ") || name.startsWith("..")

    /** Ordered by completion, so the slow step is obvious. */
    fun report(): String {
        if (done.isEmpty()) return "[e2e-timing] no steps recorded"
        val shown = done.filterNot { it.isNoise() }
        val width = (shown.maxOfOrNull { it.depth * 2 + it.name.length } ?: 40).coerceAtMost(60)
        return buildString {
            appendLine("[e2e-timing] ---- step durations ----")
            shown.forEach { e ->
                val label = " ".repeat(e.depth * 2) + e.name.take(60)
                appendLine(
                    "[e2e-timing] %-${width}s %7d ms%s".format(label, e.millis, if (e.failed) "   <-- FAILED" else "")
                )
            }
            appendLine("[e2e-timing] top-level total %d ms".format(done.filter { it.depth == 0 }.sumOf { it.millis }))
            appendLine("[e2e-timing] (%d element searches omitted)".format(done.size - shown.size))
        }
    }

    /** For a reused JVM. */
    fun reset() {
        open.clear()
        done.clear()
        failedSteps.clear()
    }
}

package com.metalbear.mirrord.products.idea

import groovy.lang.Binding
import groovy.lang.GroovyShell
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Base64

/**
 * Unit-tests the Groovy logic embedded in `mirrord-win-gradle-init.gradle.template`
 * by extracting the real marked blocks and driving them with GroovyShell against
 * stub tasks — so these assert what the injected init script actually does, not a
 * Kotlin re-implementation of it.
 *
 * Covers the production regressions on the Windows-native Gradle path:
 *  - task selection missing IntelliJ's qualified, colon-less subproject task names
 *    (`sub:bootRun` vs Gradle's `:sub:bootRun`), so the layer silently never loaded;
 *  - detecting the forked JVM's JDWP port under Debug and merging it into the child-env
 *    payload's `MIRRORD_IGNORE_DEBUGGER_PORTS`, so the layer leaves the debugger's loopback
 *    connection alone instead of resetting the handshake.
 */
class MirrordWinGradleInjectorTest {

    private val template: String by lazy { loadTemplate() }

    // --- task selection (the `sub:bootRun` regression) ---

    private fun matches(requested: List<String>, taskPath: String, taskName: String): Boolean {
        val binding = Binding().apply {
            setVariable("gradle", mapOf("startParameter" to mapOf("taskNames" to requested)))
            setVariable("candidateTask", mapOf("path" to taskPath, "name" to taskName))
        }
        val script = block("task-match") + "\nmirrordMatchesRequested(candidateTask)"
        return GroovyShell(binding).evaluate(script) as Boolean
    }

    @Test
    fun qualifiedSubprojectTaskWithoutLeadingColonMatches() {
        // The exact Dotmatics case: IntelliJ requests `sciplat-svc-audit:bootRun`,
        // Gradle's task.path is `:sciplat-svc-audit:bootRun`.
        assert(matches(listOf("sciplat-svc-audit:bootRun"), ":sciplat-svc-audit:bootRun", "bootRun")) {
            "IntelliJ passes `sub:bootRun`; Gradle task.path is `:sub:bootRun` — these must match"
        }
    }

    @Test
    fun qualifiedSubprojectTaskWithLeadingColonMatches() {
        assert(matches(listOf(":sciplat-svc-audit:bootRun"), ":sciplat-svc-audit:bootRun", "bootRun"))
    }

    @Test
    fun bareRequestedNameMatchesTaskInAnyProject() {
        assert(matches(listOf("bootRun"), ":sciplat-svc-audit:bootRun", "bootRun"))
    }

    @Test
    fun rootProjectTaskMatchesWithOrWithoutColon() {
        assert(matches(listOf(":bootRun"), ":bootRun", "bootRun"))
        assert(matches(listOf("bootRun"), ":bootRun", "bootRun"))
    }

    @Test
    fun synthesizedMainMethodTaskMatches() {
        assert(matches(listOf(":org.example.Main.main()"), ":org.example.Main.main()", "org.example.Main.main()"))
    }

    @Test
    fun qualifiedRequestDoesNotMatchSameNamedTaskInAnotherProject() {
        assert(!matches(listOf("sciplat-svc-audit:bootRun"), ":other-svc:bootRun", "bootRun")) {
            "a qualified request must not over-match bootRun in a different subproject"
        }
    }

    @Test
    fun unrelatedTaskDoesNotMatch() {
        assert(!matches(listOf("sciplat-svc-audit:bootRun"), ":sciplat-svc-audit:compileJava", "compileJava"))
    }

    @Test
    fun emptyRequestMatchesNothing() {
        assert(!matches(emptyList(), ":sciplat-svc-audit:bootRun", "bootRun"))
    }

    // --- merging the detected JDWP port into the MIRRORD_CHILD_ENV payload ---

    /** Runs the merge closure and returns the decoded child-env JSON (`{set, unset}`). */
    private fun mergeIgnoredPort(payload: String, port: String): Map<String, Any?> {
        Assumptions.assumeTrue(
            runCatching { Class.forName("groovy.json.JsonSlurper") }.isSuccess,
            "groovy-json not on the test classpath; skipping (Gradle's bundled Groovy provides it at runtime)"
        )
        val binding = Binding().apply {
            setVariable("mirrordPayload", payload)
            setVariable("mirrordPort", port)
        }
        val harness = """

            def merged = mirrordMergeIgnoredDebuggerPort(mirrordPayload, mirrordPort)
            new groovy.json.JsonSlurper()
                .parseText(new String(java.util.Base64.decoder.decode(merged), 'UTF-8'))
        """.trimIndent()

        @Suppress("UNCHECKED_CAST")
        return GroovyShell(binding).evaluate(block("merge-ignore-port") + harness) as Map<String, Any?>
    }

    @Test
    fun mergesDetectedPortAheadOfTheExistingIgnoreRange() {
        // The plugin already sets a 35000-65535 range on the payload; the detected port must
        // survive alongside it, since pitm rebuilds the child env from this payload (a plain
        // spec.environment would be overwritten by the payload's own value).
        val payload = Base64.getEncoder().encodeToString(
            """{"set":{"MIRRORD_IGNORE_DEBUGGER_PORTS":"35000-65535","FOO":"bar"},"unset":[]}""".toByteArray()
        )

        @Suppress("UNCHECKED_CAST")
        val set = mergeIgnoredPort(payload, "29964")["set"] as Map<String, Any?>
        assert(set["MIRRORD_IGNORE_DEBUGGER_PORTS"] == "29964,35000-65535") {
            "detected port must be prepended to the existing ignore range so both are honoured"
        }
        assert(set["FOO"] == "bar") { "other child-env vars must be preserved" }
    }

    @Test
    fun mergesDetectedPortWhenNoExistingIgnoreRange() {
        val payload = Base64.getEncoder().encodeToString("""{"set":{"FOO":"bar"}}""".toByteArray())

        @Suppress("UNCHECKED_CAST")
        val set = mergeIgnoredPort(payload, "5005")["set"] as Map<String, Any?>
        assert(set["MIRRORD_IGNORE_DEBUGGER_PORTS"] == "5005")
    }

    // --- JDWP debugger-port detection (so the layer ignores the debugger's loopback port) ---

    private fun detectDebuggerPort(jvmArgs: List<String>): String? {
        val binding = Binding().apply { setVariable("jvmArgs", jvmArgs) }
        val script = block("debugger-port") + "\nmirrordDetectDebuggerPort(jvmArgs)"
        return GroovyShell(binding).evaluate(script) as String?
    }

    @Test
    fun detectsJdwpPortFromIdeaForkedJvmArgs() {
        // IntelliJ's ijJvmDebugger appends this to the forked JavaExec JVM.
        assert(
            detectDebuggerPort(
                listOf(
                    "-Dfile.encoding=UTF-8",
                    "-agentlib:jdwp=transport=dt_socket,server=n,suspend=y,address=127.0.0.1:29964",
                    "-Xmx512m"
                )
            ) == "29964"
        )
    }

    @Test
    fun detectsJdwpPortWithLocalhostHostAndAddressNotLast() {
        assert(detectDebuggerPort(listOf("-agentlib:jdwp=transport=dt_socket,suspend=y,server=n,address=localhost:54898")) == "54898")
        assert(detectDebuggerPort(listOf("-agentlib:jdwp=transport=dt_socket,address=127.0.0.1:44447,suspend=y,server=n")) == "44447")
    }

    @Test
    fun detectsBarePortWhenAddressHasNoHost() {
        assert(detectDebuggerPort(listOf("-agentlib:jdwp=transport=dt_socket,server=n,suspend=y,address=5005")) == "5005")
    }

    @Test
    fun returnsNullWhenNotDebugging() {
        assert(detectDebuggerPort(listOf("-Xmx512m", "-Dfoo=bar")) == null) {
            "no jdwp agent (Run mode) must yield null so MIRRORD_IGNORE_DEBUGGER_PORTS is not set"
        }
    }

    // --- helpers ---

    /** Slices the Groovy between `// mirrord-test:<name>:start` and `:end` markers. */
    private fun block(name: String): String {
        val start = "// mirrord-test:$name:start"
        val end = "// mirrord-test:$name:end"
        val startIdx = template.indexOf(start)
        val endIdx = template.indexOf(end)
        require(startIdx >= 0 && endIdx > startIdx) {
            "markers for `$name` not found in template; did they get renamed?"
        }
        return template.substring(startIdx + start.length, endIdx)
    }

    private fun loadTemplate(): String {
        val resourcePath = "/com/metalbear/mirrord/products/idea/mirrord-win-gradle-init.gradle.template"
        javaClass.getResourceAsStream(resourcePath)?.use { return it.bufferedReader().readText() }
        // Fallback for when the idea module's resources aren't on the test classpath:
        // read straight from the source tree (test cwd is the Gradle project dir).
        val fromSource = File(
            "modules/products/idea/src/main/resources/com/metalbear/mirrord/products/idea/" +
                "mirrord-win-gradle-init.gradle.template"
        )
        if (fromSource.isFile) return fromSource.readText()
        error("could not locate mirrord-win-gradle-init.gradle.template on classpath or in source tree")
    }
}

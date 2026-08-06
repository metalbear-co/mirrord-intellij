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
 *  - Gradle 8.14 keeping `allJvmArgs` stale after IntelliJ adds JDWP to `jvmArgs` in
 *    a task action, so the JVM relaunched through mirrord silently lost its debugger;
 *  - detecting the forked JVM's JDWP port under Debug and merging it into the child-env
 *    payload's `MIRRORD_IGNORE_DEBUGGER_PORTS`, so the layer leaves the debugger's loopback
 *    connection alone instead of resetting the handshake.
 */
class MirrordWinGradleInjectorTest {

    private val template: String by lazy { loadTemplate() }

    @Test
    fun rejectsGradleOlderThan67() {
        val failure = gradleVersionFailure("6.6.1")

        assert(failure?.contains("requires Gradle 6.7 or newer") == true)
        assert(failure?.contains("this build uses 6.6.1") == true)
        assert(failure?.contains("distribution selected in IntelliJ") == true)
    }

    @Test
    fun acceptsSupportedGradleVersions() {
        assert(gradleVersionFailure("6.7") == null)
        assert(gradleVersionFailure("8.14") == null)
        assert(gradleVersionFailure("9.5.1") == null)
    }

    private fun gradleVersionFailure(version: String): String? {
        val binding = Binding().apply {
            setVariable("currentVersionText", version)
            setVariable("currentVersionOrder", if (version == "6.6.1") -1 else 0)
        }
        val script = """
            class TestVersion implements Comparable<TestVersion> {
                String version
                int order

                int compareTo(TestVersion other) {
                    order <=> other.order
                }
            }

            def currentVersion = new TestVersion(
                version: currentVersionText,
                order: currentVersionOrder
            )
            def minimumVersion = new TestVersion(version: '6.7', order: 0)
            ${block("gradle-version")}
            def result = null
            try {
                mirrordRequireSupportedGradle(currentVersion, minimumVersion)
            } catch (RuntimeException error) {
                result = error.message
            }
            result
        """.trimIndent().replace("GradleException", "RuntimeException")

        return GroovyShell(binding).evaluate(script) as String?
    }

    private fun effectiveJvmArgs(allJvmArgs: List<String>, taskJvmArgs: List<String>): List<String> {
        val binding = Binding().apply {
            setVariable("allJvmArgs", allJvmArgs)
            setVariable("taskJvmArgs", taskJvmArgs)
        }
        val script = block("effective-jvm-args") + "\nmirrordEffectiveJvmArgs(allJvmArgs, taskJvmArgs)"

        @Suppress("UNCHECKED_CAST")
        return GroovyShell(binding).evaluate(script) as List<String>
    }

    @Test
    fun mergesJdwpAddedAfterGradle814SnapshotsAllJvmArgs() {
        val jdwp = "-agentlib:jdwp=transport=dt_socket,server=n,suspend=y,address=127.0.0.1:62312"
        val allJvmArgs = listOf("-Xmx512m", "-Dfile.encoding=UTF-8", "-Duser.language=en", "-Duser.country=US")

        val effective = effectiveJvmArgs(allJvmArgs, listOf(jdwp))

        assert(effective == allJvmArgs + jdwp)
    }

    @Test
    fun doesNotDuplicateTaskJvmArgsAlreadyIncludedByNewerGradle() {
        val jdwp = "-agentlib:jdwp=transport=dt_socket,server=n,suspend=y,address=127.0.0.1:62312"
        val allJvmArgs = listOf("-Xmx512m", jdwp, "-Dfile.encoding=UTF-8")

        assert(effectiveJvmArgs(allJvmArgs, listOf(jdwp)) == allJvmArgs)
    }

    @Test
    fun preservesMissingDuplicateTaskJvmArgs() {
        val duplicate = "-Dcustomer.option=value"

        assert(effectiveJvmArgs(listOf(duplicate), listOf(duplicate, duplicate)) == listOf(duplicate, duplicate))
    }

    private fun applicationArgs(
        mainModule: String?,
        mainClass: String?,
        classpath: List<File>,
        args: List<String> = emptyList(),
        providerArgs: List<List<String>> = emptyList()
    ): List<String> {
        val binding = Binding().apply {
            setVariable("boundMainModule", mainModule)
            setVariable("boundMainClass", mainClass)
            setVariable("boundClasspath", classpath)
            setVariable("boundArgs", args)
            setVariable("boundProviderArgs", providerArgs)
        }
        val harness = """
            def property = { value -> new Expando(getOrNull: { value }) }
            def task = new Expando(
                path: ':run',
                mainModule: property(boundMainModule),
                mainClass: property(boundMainClass),
                classpath: new Expando(
                    asPath: boundClasspath.collect { it.absolutePath }.join(File.pathSeparator),
                    files: boundClasspath as Set
                ),
                args: boundArgs,
                argumentProviders: boundProviderArgs.collect { values ->
                    new Expando(asArguments: { values })
                }
            )
            mirrordApplicationArgs(task)
        """.trimIndent()

        @Suppress("UNCHECKED_CAST")
        return GroovyShell(binding).evaluate(
            (block("escape-arg") + block("application-args") + harness)
                .replace("GradleException", "RuntimeException")
        ) as List<String>
    }

    private fun escapedArg(value: String): String =
        "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

    @Test
    fun preservesClasspathLaunchArguments() {
        val classpath = listOf(File("customer-app.jar"), File("customer dependency.jar"))

        assert(
            applicationArgs(
                mainModule = null,
                mainClass = "com.customer.Main",
                classpath = classpath,
                args = listOf("customer argument"),
                providerArgs = listOf(listOf("provider argument"))
            ) == listOf(
                "-cp",
                escapedArg(classpath.joinToString(File.pathSeparator) { it.absolutePath }),
                escapedArg("com.customer.Main"),
                escapedArg("customer argument"),
                escapedArg("provider argument")
            )
        )
    }

    @Test
    fun preservesModularLaunchArguments() {
        val classpath = listOf(File("customer-module.jar"))

        assert(
            applicationArgs("customer.module", "com.customer.Main", classpath) ==
                listOf(
                    "--module-path",
                    escapedArg(classpath.single().absolutePath),
                    "--module",
                    escapedArg("customer.module/com.customer.Main")
                )
        )
    }

    @Test
    fun preservesExecutableJarLaunchArguments() {
        val jar = File("customer-app.jar")

        assert(
            applicationArgs(null, null, listOf(jar)) ==
                listOf("-jar", escapedArg(jar.absolutePath))
        )
    }

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
        // A real qualified-subproject case: IntelliJ requests `acme-svc-audit:bootRun`,
        // Gradle's task.path is `:acme-svc-audit:bootRun`.
        assert(matches(listOf("acme-svc-audit:bootRun"), ":acme-svc-audit:bootRun", "bootRun")) {
            "IntelliJ passes `sub:bootRun`; Gradle task.path is `:sub:bootRun` — these must match"
        }
    }

    @Test
    fun qualifiedSubprojectTaskWithLeadingColonMatches() {
        assert(matches(listOf(":acme-svc-audit:bootRun"), ":acme-svc-audit:bootRun", "bootRun"))
    }

    @Test
    fun bareRequestedNameMatchesTaskInAnyProject() {
        assert(matches(listOf("bootRun"), ":acme-svc-audit:bootRun", "bootRun"))
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
        assert(!matches(listOf("acme-svc-audit:bootRun"), ":other-svc:bootRun", "bootRun")) {
            "a qualified request must not over-match bootRun in a different subproject"
        }
    }

    @Test
    fun unrelatedTaskDoesNotMatch() {
        assert(!matches(listOf("acme-svc-audit:bootRun"), ":acme-svc-audit:compileJava", "compileJava"))
    }

    @Test
    fun emptyRequestMatchesNothing() {
        assert(!matches(emptyList(), ":acme-svc-audit:bootRun", "bootRun"))
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
    fun detectsLegacyJdwpPort() {
        assert(
            detectDebuggerPort(
                listOf("-Xrunjdwp:server=n,address=127.0.0.1:5006,transport=dt_socket,suspend=y")
            ) == "5006"
        )
    }

    @Test
    fun detectsExplicitJdwpAgentPathPort() {
        assert(
            detectDebuggerPort(
                listOf(
                    "-agentpath:C:\\Java\\bin\\jdwp.dll=" +
                        "server=n,address=127.0.0.1:5008,transport=dt_socket,suspend=y"
                )
            ) == "5008"
        )
    }

    @Test
    fun returnsNullWhenNotDebugging() {
        assert(detectDebuggerPort(listOf("-Xmx512m", "-Dfoo=bar")) == null) {
            "no jdwp agent (Run mode) must yield null so MIRRORD_IGNORE_DEBUGGER_PORTS is not set"
        }
    }

    @Test
    fun rejectsInvalidJdwpPorts() {
        listOf("0", "65536", "not-a-port").forEach { port ->
            assert(
                detectDebuggerPort(
                    listOf("-agentlib:jdwp=transport=dt_socket,address=$port")
                ) == null
            ) {
                "invalid JDWP port `$port` must not pass the Debug launch guard"
            }
        }
    }

    private fun debuggerState(
        jvmArgs: List<String>,
        environment: Map<String, String> = emptyMap()
    ): Map<String, Any?> {
        val binding = Binding().apply {
            setVariable("jvmArgs", jvmArgs)
            setVariable("environment", environment)
        }
        val script =
            block("debugger-port") +
                block("debugger-state") +
                "\nmirrordDebuggerState(jvmArgs, environment)"

        @Suppress("UNCHECKED_CAST")
        return GroovyShell(binding).evaluate(script) as Map<String, Any?>
    }

    private fun debuggerFailureReason(summary: Map<String, Any?>): String? {
        val binding = Binding().apply { setVariable("summary", summary) }
        val script =
            block("debugger-guard") +
                "\nmirrordDebuggerFailureReason(summary)"
        return GroovyShell(binding).evaluate(script) as String?
    }

    @Test
    fun reportsMissingJdwpWithoutLoggingJvmArguments() {
        val result = debuggerState(listOf("-Xmx512m", "-Dcustomer.secret=do-not-log"))

        assert(result["jdwpPresent"] == false)
        assert(result["jdwpArgumentCount"] == 0)
        assert(result["transportPresent"] == false)
        assert(result["dtSocketPresent"] == false)
        assert(result["addressPresent"] == false)
        assert(result["serverMode"] == "missing")
        assert(result["suspendMode"] == "missing")
        assert(result["taskDebuggerPort"] == null)
        assert(result["debuggerPort"] == null)
        assert(result["debuggerPortSource"] == "none")
        assert(result["jvmArgfilePresent"] == false)
        assert(result["jdwpEnvironmentSources"] == emptyList<String>())
        assert(result["jvmOptionArgfileSources"] == emptyList<String>())
        assert(debuggerFailureReason(result) == "the effective application JVM arguments do not contain JDWP")
    }

    @Test
    fun reportsJdwpAndItsPort() {
        val result = debuggerState(
            listOf("-agentlib:jdwp=transport=dt_socket,server=n,suspend=y,address=127.0.0.1:51011")
        )

        assert(result["jdwpPresent"] == true)
        assert(result["jdwpArgumentCount"] == 1)
        assert(result["transportPresent"] == true)
        assert(result["dtSocketPresent"] == true)
        assert(result["addressPresent"] == true)
        assert(result["serverMode"] == "client")
        assert(result["suspendMode"] == "enabled")
        assert(result["taskDebuggerPort"] == "51011")
        assert(result["debuggerPort"] == "51011")
        assert(result["debuggerPortSource"] == "task_jvm_args")
        assert(debuggerFailureReason(result) == null)
    }

    @Test
    fun distinguishesUnparseableJdwpFromMissingJdwp() {
        val result = debuggerState(
            listOf("-agentlib:jdwp=transport=dt_socket,server=n,suspend=y")
        )

        assert(result["jdwpPresent"] == true)
        assert(result["dtSocketPresent"] == true)
        assert(result["addressPresent"] == false)
        assert(result["debuggerPort"] == null)
        assert(
            debuggerFailureReason(result) ==
                "a dt_socket JDWP argument is present, but it has no address"
        )
    }

    @Test
    fun rejectsJdwpWithAnInvalidSocketPort() {
        val result = debuggerState(
            listOf("-agentlib:jdwp=transport=dt_socket,server=n,address=127.0.0.1:70000")
        )

        assert(result["addressPresent"] == true)
        assert(result["taskDebuggerPort"] == null)
        assert(
            debuggerFailureReason(result) ==
                "a dt_socket JDWP address is present, but its port could not be parsed"
        )
    }

    @Test
    fun reportsJdwpListenerModeAndDisabledSuspend() {
        val result = debuggerState(
            listOf(
                "-agentpath:C:\\Java\\bin\\jdwp.dll=" +
                    "transport=dt_socket,address=*:5008,server=y,suspend=n"
            )
        )

        assert(result["jdwpPresent"] == true)
        assert(result["serverMode"] == "listener")
        assert(result["suspendMode"] == "disabled")
        assert(result["debuggerPort"] == "5008")
        assert(debuggerFailureReason(result) == null)
    }

    @Test
    fun reportsUnsupportedJdwpTransport() {
        val result = debuggerState(
            listOf("-agentlib:jdwp=transport=dt_shmem,address=customer-debug,server=y")
        )

        assert(result["jdwpPresent"] == true)
        assert(result["transportPresent"] == true)
        assert(result["dtSocketPresent"] == false)
        assert(result["addressPresent"] == true)
        assert(result["serverMode"] == "listener")
        assert(result["debuggerPort"] == null)
        assert(debuggerFailureReason(result) == null) {
            "dt_shmem is a valid Windows JDWP transport and must not be rejected for lacking a socket port"
        }
    }

    @Test
    fun reportsJdwpHiddenInJvmOptionEnvironmentWithoutLoggingItsValue() {
        val result = debuggerState(
            jvmArgs = listOf("-Xmx512m"),
            environment = mapOf(
                "JAVA_TOOL_OPTIONS" to
                    "-Dcustomer.secret=do-not-log " +
                    "-agentlib:jdwp=transport=dt_socket,address=127.0.0.1:5005"
            )
        )

        assert(result["jdwpPresent"] == false)
        assert(result["taskDebuggerPort"] == null)
        assert(result["debuggerPort"] == "5005")
        assert(result["debuggerPortSource"] == "JAVA_TOOL_OPTIONS")
        assert(result["jdwpEnvironmentSources"] == listOf("JAVA_TOOL_OPTIONS"))
        assert(debuggerFailureReason(result) == null)
    }

    @Test
    fun doesNotReportJdwpFromAnUnrelatedSystemPropertyValue() {
        val result = debuggerState(
            jvmArgs = listOf("-Xmx512m"),
            environment = mapOf(
                "JAVA_TOOL_OPTIONS" to "-Dnote=-agentlib:jdwp=transport=dt_socket,address=5005"
            )
        )

        assert(result["jdwpEnvironmentSources"] == emptyList<String>())
    }

    @Test
    fun reportsJvmOptionEnvironmentArgumentFilesWithoutLoggingTheirPaths() {
        val result = debuggerState(
            jvmArgs = listOf("-Xmx512m"),
            environment = mapOf("JDK_JAVA_OPTIONS" to "@C:\\customer\\private\\debug.args")
        )

        assert(result["jdwpEnvironmentSources"] == emptyList<String>())
        assert(result["jvmOptionArgfileSources"] == listOf("JDK_JAVA_OPTIONS"))
        assert(debuggerFailureReason(result) == null) {
            "an environment argument file is indeterminate and must be allowed to reach the JVM"
        }
    }

    @Test
    fun treatsTaskJvmArgumentFilesAsIndeterminate() {
        val result = debuggerState(listOf("@C:\\customer\\private\\debug.args"))

        assert(result["jvmArgfilePresent"] == true)
        assert(debuggerFailureReason(result) == null)
    }

    @Test
    fun reportsOptionlessJdwpAsPresentButMalformed() {
        listOf(
            "-agentlib:jdwp",
            "-Xrunjdwp",
            "-agentpath:C:\\Java\\bin\\jdwp.dll"
        ).forEach { argument ->
            val result = debuggerState(listOf(argument))

            assert(result["jdwpPresent"] == true)
            assert(result["transportPresent"] == false)
            assert(result["dtSocketPresent"] == false)
            assert(result["addressPresent"] == false)
            assert(result["debuggerPort"] == null)
            assert(
                debuggerFailureReason(result) ==
                    "a JDWP argument is present, but it has no transport"
            )
        }
    }

    @Test
    fun completeRenderedTemplateCompilesAsGroovy() {
        val rendered = template
            .replace(
                "__MIRRORD_CLI_PATH_BASE64__",
                Base64.getEncoder().encodeToString("C:/Users/O'Connor/mirrord.exe".toByteArray())
            )
            .replace("__MIRRORD_CHILD_ENV_VAR__", "MIRRORD_CHILD_ENV")
            .replace("__MIRRORD_DEBUG_EXPECTED__", "true")
            .replace("import javax.inject.Inject", "")
            .replace("import org.gradle.api.tasks.JavaExec", "")
            .replace("import org.gradle.process.ExecOperations", "")
            .replace("import org.gradle.util.GradleVersion", "")
            .replace(
                "mirrordRequireSupportedGradle(GradleVersion.current(), GradleVersion.version('6.7'))",
                ""
            )
            .replace(
                Regex(
                    """abstract class MirrordExecInjector \{\s*""" +
                        """@Inject abstract ExecOperations getExecOps\(\)\s*}"""
                ),
                "class MirrordExecInjector { def execOps }"
            )
            .replace("JavaExec", "Object")
            .replace("GradleException", "RuntimeException")

        GroovyShell().parse(rendered)
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

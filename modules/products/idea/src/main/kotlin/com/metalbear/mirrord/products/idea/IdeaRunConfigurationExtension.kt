@file:Suppress("UnstableApiUsage")

package com.metalbear.mirrord.products.idea

import com.intellij.debugger.engine.DebugProcess
import com.intellij.debugger.engine.DebugProcessImpl
import com.intellij.debugger.engine.DebugProcessListener
import com.intellij.debugger.engine.JavaDebugProcess
import com.intellij.debugger.engine.events.DebuggerCommandImpl
import com.intellij.debugger.impl.GenericDebuggerRunnerSettings
import com.intellij.execution.CommonProgramRunConfigurationParameters
import com.intellij.execution.RunConfigurationExtension
import com.intellij.execution.configurations.JavaParameters
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.target.createEnvironmentRequest
import com.intellij.execution.wsl.target.WslTargetEnvironmentRequest
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemRunConfiguration
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerManagerListener
import com.metalbear.mirrord.MirrordBinaryManager
import com.metalbear.mirrord.MirrordLogger
import com.metalbear.mirrord.MirrordPitm
import com.metalbear.mirrord.MirrordProjectService
import com.metalbear.mirrord.isWinNative
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Whether the IDE is launching a JVM debug session (vs. plain run). */
internal val RunnerSettings?.isJvmDebug: Boolean
    get() = this is GenericDebuggerRunnerSettings

/**
 * Whether this run configuration is handled by IntelliJ's external build-system
 * integration (Gradle, Maven, SBT, Bazel-tasks). Contract-enabled so the caller
 * gets a smart-cast to [ExternalSystemRunConfiguration] in the `true` branch.
 */
@OptIn(kotlin.contracts.ExperimentalContracts::class)
internal fun RunConfigurationBase<*>.isExternalBuildSystem(): Boolean {
    kotlin.contracts.contract {
        returns(true) implies (this@isExternalBuildSystem is ExternalSystemRunConfiguration)
    }
    return this is ExternalSystemRunConfiguration
}

/**
 * For overriding the `isApplicableFor` check on run configurations.
 * Set MIRRORD_FORCE_RUN=true to ensure we consider the run configuration able to run with mirrord.
 * NOTE: mirrord must _still be enabled_ to run on the run configuration.
 */
const val FORCE_RUN_ENV_NAME: String = "MIRRORD_FORCE_RUN"

private const val GRADLE_RUN_CONFIGURATION = "org.jetbrains.plugins.gradle.service.execution.GradleRunConfiguration"

/**
 * Exact task names that never fork a user JVM (packaging / clean / docs / housekeeping).
 */
private val GRADLE_BUILD_ONLY_EXACT = setOf(
    "clean",
    "jar", "war", "bootJar", "bootWar", "shadowJar", "distTar", "distZip",
    "javadoc",
    "wrapper", "init",
    "classes", "testClasses"
)

/**
 * Prefix matches for build-only tasks. Covers multiplatform / Android / custom
 * source-set variants: `compileJava`, `compileKotlinJvm`, `compileDebugJavaWithJavac`,
 * `compileIntegrationTestJava`, `processResources`, `processTestResources`, etc.
 */
private val GRADLE_BUILD_ONLY_PREFIXES = listOf(
    "compile",
    "process" // processResources, process*Resources
)

private fun isGradleBuildOnlyTask(task: String): Boolean {
    val bare = task.removePrefix(":").substringAfterLast(':')
    return GRADLE_BUILD_ONLY_EXACT.any { bare.equals(it, ignoreCase = true) } ||
        GRADLE_BUILD_ONLY_PREFIXES.any { bare.startsWith(it, ignoreCase = true) }
}

internal fun isIdeaConfigurationApplicableForMirrord(configuration: RunConfigurationBase<*>): Boolean {
    val skipTomcat = configuration.name.startsWith("Build ") || configuration.name.startsWith("Tomcat")

    val gradleTaskNames = (configuration as? ExternalSystemRunConfiguration)?.settings?.taskNames ?: emptyList()
    val skipGradleBuild = configuration.javaClass.name == GRADLE_RUN_CONFIGURATION &&
        gradleTaskNames.isNotEmpty() &&
        gradleTaskNames.all(::isGradleBuildOnlyTask)

    val forceRunMirrord = getForceRunMirrord(configuration)

    return forceRunMirrord || !(skipTomcat || skipGradleBuild)
}

internal fun getIdeaConfigurationEnv(configuration: RunConfigurationBase<*>): Map<String, String> {
    return when (configuration) {
        is ExternalSystemRunConfiguration -> configuration.settings.env
        is CommonProgramRunConfigurationParameters -> configuration.envs
        else -> emptyMap()
    }
}

private fun getForceRunMirrord(configuration: RunConfigurationBase<*>): Boolean {
    return if (configuration is ExternalSystemRunConfiguration) {
        configuration.settings.env[FORCE_RUN_ENV_NAME].toBoolean()
    } else {
        false
    }
}

class IdeaRunConfigurationExtension : RunConfigurationExtension() {
    /**
     * mirrord env set in ExternalRunConfigurations. Used for cleanup the configuration after the execution has ended.
     */
    private val runningProcessEnvs = ConcurrentHashMap<RunConfigurationBase<*>, Map<String, String>>()
    private val runningProcessScriptParams = ConcurrentHashMap<RunConfigurationBase<*>, String>()

    override fun isApplicableFor(configuration: RunConfigurationBase<*>): Boolean {
        val applicable = isIdeaConfigurationApplicableForMirrord(configuration)

        if (!applicable) {
            MirrordLogger.logger.info("Configuration name %s ignored".format(configuration.name))
        }

        return applicable
    }

    override fun isEnabledFor(
        applicableConfiguration: RunConfigurationBase<*>,
        runnerSettings: RunnerSettings?
    ): Boolean {
        return true
    }

    override fun <T : RunConfigurationBase<*>> updateJavaParameters(
        configuration: T,
        params: JavaParameters,
        runnerSettings: RunnerSettings?
    ) {
        val configSummary = "cfg=`${configuration.name}` class=${configuration.javaClass.simpleName} " +
            "runnerSettings=${runnerSettings?.javaClass?.simpleName ?: "null"} " +
            "isExternal=${configuration.isExternalBuildSystem()}"
        MirrordLogger.logger.info("updateJavaParameters: ENTER $configSummary")

        val executionInfo = IdeaMirrordPreparationStore.consume(configuration) ?: run {
            // Main mirrord initialization is prepared by a before-run task to avoid blocking here under read lock.
            MirrordLogger.logger.info("updateJavaParameters: no prepared executionInfo for `${configuration.name}`, skipping (check IdeaExecutionListener/BeforeRunTask ran)")
            return
        }

        val mirrordEnv = executionInfo.environment + mapOf("MIRRORD_DETECT_DEBUGGER_PORT" to "javaagent")
        val envToUnset = executionInfo.envToUnset
        MirrordLogger.logger.info(
            "updateJavaParameters: executionInfo consumed, mirrordEnv size=${mirrordEnv.size}, envToUnset size=${envToUnset?.size ?: 0}"
        )

        // Resolve WSL for platform gating (Windows-native-only pitm/attach paths).
        @Suppress("UnstableApiUsage")
        val wsl = when (val request = createEnvironmentRequest(configuration, configuration.project)) {
            is WslTargetEnvironmentRequest -> request.configuration.distribution!!
            else -> null
        }
        val winNative = isWinNative(wsl)
        val isDebug = runnerSettings.isJvmDebug
        MirrordLogger.logger.info(
            "updateJavaParameters: platform gating winNative=$winNative wsl=${wsl?.presentableName ?: "null"} isDebug=$isDebug"
        )

        // On Windows native, try to wrap the JDK with mirrord pitm. When that
        // succeeds, the mirrord env vars are ferried to the child via
        // MIRRORD_CHILD_ENV only — they must NOT be set on params.env directly,
        // or the mirrord.exe wrapper itself would inherit them.
        //
        // ExternalSystemRunConfiguration (Gradle/Maven) is excluded because
        // params.jdk is null — Gradle manages its own JDK. For Gradle debug
        // on Windows we use mirrord-attach instead (armIdeaDebugAttach below).
        val envSizeBefore = params.env.size
        val pitmWrapped = if (winNative && !configuration.isExternalBuildSystem()) {
            MirrordLogger.logger.info("updateJavaParameters: branch=NON_GRADLE_WIN, attempting wrapJdkWithPitm")
            wrapJdkWithPitm(configuration.project, params, mirrordEnv, envToUnset)
        } else {
            false
        }

        if (!pitmWrapped) {
            params.env = params.env + mirrordEnv - envToUnset.orEmpty().toSet()
            MirrordLogger.logger.info(
                "updateJavaParameters: mirrord env set on params.env directly (pitm NOT engaged), " +
                    "params.env size ${envSizeBefore} → ${params.env.size}"
            )
        } else {
            MirrordLogger.logger.info(
                "updateJavaParameters: pitm JDK wrap succeeded, params.env size ${envSizeBefore} → ${params.env.size}"
            )
        }

        // Gradle support (and external system configuration)
        if (configuration.isExternalBuildSystem()) {
            val gradleBranch = when {
                winNative && !isDebug -> "GRADLE_WIN_RUN"
                winNative && isDebug -> "GRADLE_WIN_DEBUG"
                else -> "GRADLE_PLAIN_ENV"
            }
            MirrordLogger.logger.info(
                "updateJavaParameters: ExternalSystem branch=$gradleBranch taskNames=${configuration.settings.taskNames}"
            )

            runningProcessEnvs[configuration] = configuration.settings.env.toMap()
            configuration.settings.scriptParameters?.let {
                runningProcessScriptParams[configuration] = it
            }
            MirrordLogger.logger.debug(
                "updateJavaParameters: snapshotted settings.env size=${configuration.settings.env.size}, " +
                    "scriptParameters=${configuration.settings.scriptParameters}"
            )

            if (winNative && !isDebug) {
                // Windows native + Run: inject a Gradle init script that wraps
                // JavaExec tasks with `mirrord pitm`, and ferry env vars via
                // MIRRORD_CHILD_ENV so they only reach the child process.
                wrapGradleRunWithPitm(configuration, mirrordEnv, envToUnset)
            } else {
                // Non-Windows, or Debug: set env vars directly on the config.
                val env = configuration.settings.env + mirrordEnv - envToUnset.orEmpty().toSet()
                configuration.settings.env = env
                MirrordLogger.logger.info(
                    "updateJavaParameters: settings.env updated directly, now ${configuration.settings.env.size} vars"
                )
            }

            // Windows native + Debug: hook the JDWP ATTACHED event to hold the
            // VM suspended (via JDI suspend count) while we inject the layer.
            if (winNative && isDebug) {
                armIdeaDebugAttach(configuration.project, mirrordEnv)
            }
        }
        MirrordLogger.logger.info("updateJavaParameters: EXIT $configSummary")
    }

    /**
     * Replaces `params.jdk` with a fake JDK whose `bin/java.exe` is actually
     * `mirrord.exe`. This is necessary because IntelliJ builds the java command
     * line as `<jdk.homePath>/bin/java.exe <args>` — there is no other way to
     * override the executable for a `JavaParameters`-based launch.
     *
     * When IntelliJ invokes this fake `java.exe`, mirrord's `run_as_java_launcher`
     * detects `argv[0]` is `java.exe` and enters pitm mode using:
     * (https://github.com/metalbear-co/mirrord/blob/main/mirrord/cli/src/pitm.rs)
     *
     *   - [MirrordPitmJdk.REAL_JAVA_ENV] — path to the real `java.exe`; pitm
     *     spawns it suspended and injects the layer before resuming.
     *   - [MirrordPitm.CHILD_ENV_VAR] — base64-JSON `{set, unset}` payload
     *     that pitm decodes to build the child environment.
     *
     * Returns `true` on success. On failure the caller falls back to setting
     * [mirrordEnvVars] directly on `params.env`.
     */
    private fun wrapJdkWithPitm(
        project: Project,
        params: JavaParameters,
        mirrordEnvVars: Map<String, String>,
        envToUnset: List<String>?
    ): Boolean {
        MirrordLogger.logger.info(
            "wrapJdkWithPitm: ENTER jdk=${params.jdk?.name ?: "null"} jdkHome=${params.jdk?.homePath ?: "null"} " +
                "mirrordEnvVars=${mirrordEnvVars.size} envToUnset=${envToUnset?.size ?: 0}"
        )

        val realJdk = params.jdk
        if (realJdk == null) {
            MirrordLogger.logger.error("wrapJdkWithPitm: no JDK on run config, aborting")
            project.service<MirrordProjectService>().notifier.notifySimple(
                "mirrord: no JDK on run config; pitm wrap failed",
                NotificationType.ERROR
            )
            return false
        }
        val realHome = realJdk.homePath
        if (realHome == null) {
            MirrordLogger.logger.error("wrapJdkWithPitm: JDK `${realJdk.name}` has no homePath, aborting")
            project.service<MirrordProjectService>().notifier.notifySimple(
                "mirrord: JDK `${realJdk.name}` has no homePath; pitm wrap failed",
                NotificationType.ERROR
            )
            return false
        }

        val mirrordExe = try {
            File(service<MirrordBinaryManager>().getBinary("idea", null, project))
        } catch (e: Exception) {
            MirrordLogger.logger.warn("wrapJdkWithPitm: failed to resolve mirrord binary: ${e.message}", e)
            project.service<MirrordProjectService>().notifier.notifySimple(
                "mirrord: could not resolve mirrord binary for pitm wrap (${e.message}); layer will not load",
                NotificationType.ERROR
            )
            return false
        }
        MirrordLogger.logger.debug(
            "wrapJdkWithPitm: mirrordExe=${mirrordExe.absolutePath} exists=${mirrordExe.isFile} size=${if (mirrordExe.isFile) mirrordExe.length() else -1}"
        )

        val wrapped = MirrordPitmJdk.wrap(realJdk, mirrordExe) ?: run {
            MirrordLogger.logger.warn("wrapJdkWithPitm: MirrordPitmJdk.wrap returned null — see MirrordPitmJdk logs above")
            project.service<MirrordProjectService>().notifier.notifySimple(
                "mirrord: fake JDK preparation failed; pitm wrap skipped",
                NotificationType.WARNING
            )
            return false
        }

        val childEnvPayload = MirrordPitm.encodeChildEnv(mirrordEnvVars, envToUnset)

        val envSizeBefore = params.env.size
        params.jdk = wrapped
        params.env = params.env + mapOf(
            MirrordPitmJdk.REAL_JAVA_ENV to "$realHome/bin/java.exe",
            MirrordPitm.CHILD_ENV_VAR to childEnvPayload
        )
        // Guardrail: if any raw mirrord env vars leaked onto params.env, the
        // mirrord.exe wrapper itself would inherit them. Detect and loudly warn.
        val leaked = params.env.keys.intersect(mirrordEnvVars.keys)
        if (leaked.isNotEmpty()) {
            MirrordLogger.logger.warn(
                "wrapJdkWithPitm: LEAK — mirrord env vars present on params.env after pitm wrap: $leaked. " +
                    "These should only live inside MIRRORD_CHILD_ENV."
            )
        }
        MirrordLogger.logger.info(
            "wrapJdkWithPitm: SUCCESS realJava=$realHome/bin/java.exe " +
                "childEnvPayload.len=${childEnvPayload.length} " +
                "params.env ${envSizeBefore} → ${params.env.size} (+REAL_JAVA, +CHILD_ENV)"
        )
        return true
    }

    private fun resolveCliPath(project: Project): String =
        service<MirrordBinaryManager>().getCliPath("idea", null, project)

    /**
     * Creates a Gradle init script that wraps `JavaExec` tasks with `mirrord pitm`
     * for suspended-start layer injection on Windows.
     *
     * The init script removes only `JavaExec`'s built-in `@TaskAction` (preserving
     * IDE-injected `doFirst` actions such as the Kotlin coroutine debug agent,
     * source mapper, and JVM debugger init) and appends a `doLast` that invokes
     * `mirrord.exe pitm -- <real java> @<argfile>` via `ExecOperations`.
     *
     * An argfile is used so that large classpaths (common in real projects)
     * don't blow past Windows' 32 KiB command-line limit; `ExecOperations`
     * is used so Stop-in-IDE cancellation reliably kills the pitm child.
     * Mirrord env vars reach the child via [MirrordPitm.CHILD_ENV_VAR].
     */
    private fun wrapGradleRunWithPitm(
        configuration: ExternalSystemRunConfiguration,
        mirrordEnvVars: Map<String, String>,
        envToUnset: List<String>?
    ) {
        MirrordLogger.logger.info(
            "wrapGradleRunWithPitm: ENTER taskNames=${configuration.settings.taskNames} " +
                "mirrordEnvVars=${mirrordEnvVars.size} envToUnset=${envToUnset?.size ?: 0}"
        )

        val cliPath = resolveCliPath(configuration.project).replace("\\", "/")
        val childEnvPayload = MirrordPitm.encodeChildEnv(mirrordEnvVars, envToUnset)
        val taskFilter = gradleTaskNameFilter(configuration.settings.taskNames)
        MirrordLogger.logger.info(
            "wrapGradleRunWithPitm: cliPath=$cliPath taskFilter=[$taskFilter] childEnvPayload.len=${childEnvPayload.length}"
        )
        if (taskFilter.isBlank()) {
            MirrordLogger.logger.warn(
                "wrapGradleRunWithPitm: taskFilter is empty — init script will match no tasks. " +
                    "This usually means the Gradle config has no taskNames."
            )
            configuration.project.service<MirrordProjectService>().notifier.notifySimple(
                "mirrord: Gradle config has no task names; pitm wrap will not match any task",
                NotificationType.WARNING
            )
        }

        val initScript = try {
            createPitmInitScript(cliPath, taskFilter)
        } catch (e: Exception) {
            MirrordLogger.logger.warn("wrapGradleRunWithPitm: failed to create init script: ${e.message}", e)
            configuration.project.service<MirrordProjectService>().notifier.notifySimple(
                "mirrord: could not create pitm init script (${e.message}); layer will not load.",
                NotificationType.ERROR
            )
            val env = configuration.settings.env + mirrordEnvVars - envToUnset.orEmpty().toSet()
            configuration.settings.env = env
            return
        }

        val envBefore = configuration.settings.env.size
        val scriptParamsBefore = configuration.settings.scriptParameters ?: ""
        configuration.settings.env = configuration.settings.env + mapOf(
            MirrordPitm.CHILD_ENV_VAR to childEnvPayload
        )
        appendInitScript(configuration, initScript)

        MirrordLogger.logger.info(
            "wrapGradleRunWithPitm: SUCCESS initScript=${initScript.absolutePath} size=${initScript.length()}b, " +
                "settings.env ${envBefore} → ${configuration.settings.env.size}, " +
                "scriptParameters grew from ${scriptParamsBefore.length} to ${(configuration.settings.scriptParameters ?: "").length} chars"
        )
    }

    /** Builds a Groovy set literal from Gradle task names, stripping the `:` project-path prefix. */
    private fun gradleTaskNameFilter(taskNames: List<String>): String {
        val names = taskNames.map { it.removePrefix(":") }
        return names.joinToString(", ") { "'${it.replace("'", "\\'")}'" }
    }

    /**
     * Creates a temp Gradle init script that surgically replaces `JavaExec`'s
     * built-in action with `mirrord pitm -- <java> @<argfile>`, via
     * [org.gradle.process.ExecOperations].
     *
     * Kept as doLast (not a full action swap) to preserve IDE-injected
     * `doFirst` actions that mutate `jvmArgs` at runtime. Argfile sidesteps
     * the Windows `CreateProcess` 32 KiB command-line limit.
     */
    private fun createPitmInitScript(cliPath: String, taskFilter: String): File {
        return File.createTempFile("mirrord-pitm-", ".gradle").apply {
            deleteOnExit()
            writeText(
                """
                import javax.inject.Inject
                import org.gradle.process.ExecOperations

                abstract class MirrordExecInjector {
                    @Inject abstract ExecOperations getExecOps()
                }

                def mirrordEscapeArg = { s ->
                    '"' + String.valueOf(s).replace('\\', '\\\\').replace('"', '\\"') + '"'
                }
                def mirrordTargetTasks = [${taskFilter}] as Set
                def mirrordStandardTaskActionClass = 'org.gradle.api.internal.project.taskfactory.StandardTaskAction'

                allprojects {
                    def execOps = project.objects.newInstance(MirrordExecInjector).execOps
                    tasks.withType(JavaExec).configureEach { task ->
                        if (!mirrordTargetTasks.contains(task.name)) return
                        task.doLast {
                            def realJava = task.executable
                            def argfile = File.createTempFile('mirrord-pitm-', '.args')
                            argfile.deleteOnExit()
                            def lines = []
                            task.allJvmArgs.each { lines << mirrordEscapeArg(it) }
                            lines << '-cp'
                            lines << mirrordEscapeArg(task.classpath.asPath)
                            if (task.mainClass.isPresent()) lines << mirrordEscapeArg(task.mainClass.get())
                            (task.args ?: []).each { lines << mirrordEscapeArg(it) }
                            task.argumentProviders.each { p ->
                                p.asArguments().each { lines << mirrordEscapeArg(it) }
                            }
                            argfile.text = lines.join(System.lineSeparator())
                            execOps.exec { spec ->
                                spec.executable '${cliPath}'
                                spec.args 'pitm', '--', realJava, '@' + argfile.absolutePath
                                spec.environment(task.environment)
                                spec.environment('${MirrordPitm.CHILD_ENV_VAR}', System.getenv('${MirrordPitm.CHILD_ENV_VAR}') ?: '')
                                spec.workingDir = task.workingDir
                                spec.standardInput = System.in
                            }
                        }
                        task.actions.removeAll { it.class.name == mirrordStandardTaskActionClass }
                    }
                }
                """.trimIndent()
            )
        }
    }

    /** Appends `--init-script <path>` to the Gradle run configuration's script parameters. */
    private fun appendInitScript(configuration: ExternalSystemRunConfiguration, script: File) {
        val scriptPath = script.absolutePath.replace("\\", "/")
        val existing = configuration.settings.scriptParameters ?: ""
        configuration.settings.scriptParameters = "$existing --init-script \"$scriptPath\""
    }

    /**
     * Arms a one-shot listener that holds the target JVM suspended via JDI while
     * `mirrord attach <pid>` injects the layer DLL, then releases the hold.
     *
     * Flow:
     * 1. `processStarted` fires → register [DebugProcessListener] on [DebugProcessImpl].
     * 2. `processAttached` fires once JDWP connects → schedule [DebuggerCommandImpl]
     *    on the manager thread (required by `getVirtualMachineProxy()` assertion).
     * 3. On manager thread: `vm.suspend()` increments JDI suspend count to 2.
     *    The framework's auto-resume decrements to 1 — VM stays frozen.
     * 4. Pooled thread: netstat port→PID lookup, `mirrord attach`, then
     *    `vm.resume()` drops count to 0 — user code runs with layer loaded.
     *
     * Used for Gradle/Maven debug on Windows native where pitm cannot be used
     * (Gradle owns JVM creation). CLI injection flow:
     * https://github.com/metalbear-co/mirrord/blob/main/mirrord/cli/src/attach.rs
     */
    private fun armIdeaDebugAttach(project: Project, envVars: Map<String, String>) {
        val cliPath = resolveCliPath(project)
        MirrordLogger.logger.info("armIdeaDebugAttach: wiring listener, cliPath=$cliPath")

        val busConnection = project.messageBus.connect()
        busConnection.subscribe(
            XDebuggerManager.TOPIC,
            object : XDebuggerManagerListener {
                @Volatile
                private var wired = false

                override fun processStarted(debugProcess: XDebugProcess) {
                    if (wired) return
                    wired = true

                    val javaProcess = debugProcess as? JavaDebugProcess
                    if (javaProcess == null) {
                        MirrordLogger.logger.warn(
                            "armIdeaDebugAttach: processStarted but not JavaDebugProcess " +
                                "(got ${debugProcess::class.qualifiedName}), skipping"
                        )
                        busConnection.disconnect()
                        return
                    }

                    MirrordLogger.logger.info("armIdeaDebugAttach: processStarted, wiring DebugProcessListener")

                    // BLUF: register DebugProcessListener → on processAttached, schedule
                    // a DebuggerCommand on the manager thread → vm.suspend() to hold the
                    // VM → find target PID via netstat → mirrord attach → vm.resume().
                    val debugProcessImpl = javaProcess.debuggerSession.process
                    debugProcessImpl.addDebugProcessListener(object : DebugProcessListener {
                        override fun processAttached(process: DebugProcess) {
                            val impl = process as? DebugProcessImpl ?: return
                            debugProcessImpl.removeDebugProcessListener(this)

                            // getVirtualMachineProxy() asserts we're on the debugger
                            // manager thread. processAttached fires from commitVM which
                            // may run on a connection thread or EDT, so schedule the
                            // suspend on the manager thread. This command runs before
                            // the auto-resume command that commitVM queues afterwards.
                            impl.managerThread.schedule(object : DebuggerCommandImpl() {
                                override fun action() {
                                    val vm = try {
                                        impl.virtualMachineProxy.virtualMachine
                                    } catch (e: Exception) {
                                        MirrordLogger.logger.warn(
                                            "armIdeaDebugAttach: VM not accessible: ${e.message}"
                                        )
                                        busConnection.disconnect()
                                        return
                                    }

                                    // Extra suspend — prevents the upcoming auto-resume
                                    // from running user code.
                                    vm.suspend()
                                    MirrordLogger.logger.info(
                                        "armIdeaDebugAttach: vm.suspend() on manager thread (extra hold)"
                                    )

                                    val port: Int?
                                    val debuggerListens: Boolean
                                    try {
                                        val conn = impl.connection
                                        @Suppress("DEPRECATION")
                                        val address = conn.address
                                        port = address?.toIntOrNull()
                                        // isServerMode=true means IntelliJ (debugger) listens,
                                        // target connects TO that port.
                                        debuggerListens = conn.isServerMode
                                    } catch (e: Exception) {
                                        MirrordLogger.logger.warn(
                                            "armIdeaDebugAttach: could not read JDWP connection: ${e.message}"
                                        )
                                        vm.resume()
                                        busConnection.disconnect()
                                        return
                                    }

                                    ApplicationManager.getApplication().executeOnPooledThread {
                                        try {
                                            val pid = if (port != null) findPidByJdwpPort(port, debuggerListens) else null
                                            if (pid == null) {
                                                MirrordLogger.logger.warn(
                                                    "armIdeaDebugAttach: could not find PID for JDWP port $port " +
                                                        "(debuggerListens=$debuggerListens). Debug session will proceed WITHOUT mirrord layer."
                                                )
                                                project.service<MirrordProjectService>().notifier.notifySimple(
                                                    "mirrord: could not identify target JVM (JDWP port $port); debug session proceeding without layer",
                                                    NotificationType.WARNING
                                                )
                                                return@executeOnPooledThread
                                            }

                                            MirrordLogger.logger.info(
                                                "armIdeaDebugAttach: attaching to pid=$pid"
                                            )
                                            project.service<MirrordProjectService>()
                                                .execManager
                                                .attach(cliPath, envVars, pid)
                                            MirrordLogger.logger.info(
                                                "armIdeaDebugAttach: attach completed for pid $pid"
                                            )
                                        } catch (e: Exception) {
                                            MirrordLogger.logger.error(
                                                "armIdeaDebugAttach: attach failed", e
                                            )
                                            project.service<MirrordProjectService>().notifier.notifySimple(
                                                "mirrord attach failed: ${e.message}",
                                                NotificationType.ERROR
                                            )
                                        } finally {
                                            // Release our extra suspend — IntelliJ's auto-resume
                                            // already decremented the count, so this drops it to 0.
                                            vm.resume()
                                            MirrordLogger.logger.info(
                                                "armIdeaDebugAttach: vm.resume() (extra hold released)"
                                            )
                                            busConnection.disconnect()
                                        }
                                    }
                                }

                            })
                        }
                    })
                }
            }
        )
    }

    /**
     * Finds the PID of the target JVM by parsing `netstat -ano`.
     *
     * @param port the JDWP port from [RemoteConnection.getAddress]
     * @param debuggerListens when true IntelliJ listens on [port] and the
     *   target connects to it (match REMOTE port, ESTABLISHED). When false
     *   the target JVM listens on [port] (match LOCAL port, LISTENING).
     */
    private fun findPidByJdwpPort(port: Int, debuggerListens: Boolean): Long? {
        return try {
            val started = System.currentTimeMillis()
            val process = ProcessBuilder("cmd", "/c", "netstat -ano")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exited = process.waitFor(10, TimeUnit.SECONDS)
            val elapsed = System.currentTimeMillis() - started
            MirrordLogger.logger.info(
                "findPidByJdwpPort: netstat completed in ${elapsed}ms exited=$exited exitCode=${if (exited) process.exitValue() else -1} outputLen=${output.length}"
            )
            if (!exited) {
                MirrordLogger.logger.warn("findPidByJdwpPort: netstat did not exit within 10s for port $port")
            }

            val pid = if (debuggerListens) {
                // IntelliJ listens on :port → target is the side whose REMOTE addr is :port.
                // netstat: TCP  <local>:<localPort>  <remote>:<remotePort>  ESTABLISHED  <pid>
                // We want lines where REMOTE ends with :<port> but LOCAL is NOT :<port>
                // (to exclude IntelliJ's own side of the connection).
                val pattern = Regex("""\s+TCP\s+(\S+)\s+\S+:${port}\s+ESTABLISHED\s+(\d+)""")
                val all = pattern.findAll(output).toList()
                val filtered = all.filter { !it.groupValues[1].endsWith(":$port") }
                MirrordLogger.logger.info(
                    "findPidByJdwpPort: serverMode — ESTABLISHED matches total=${all.size} after-excluding-IDE-side=${filtered.size}"
                )
                if (filtered.size > 1) {
                    MirrordLogger.logger.warn(
                        "findPidByJdwpPort: AMBIGUOUS — ${filtered.size} distinct PIDs connect to :$port. Picking first: " +
                            filtered.joinToString(", ") { "${it.groupValues[2]}@${it.groupValues[1]}" }
                    )
                }
                filtered.firstOrNull()?.groupValues?.get(2)?.toLongOrNull()
            } else {
                // Target listens on :port → match LOCAL port, LISTENING
                val pattern = Regex("""\s+TCP\s+\S+:${port}\s+\S+\s+LISTENING\s+(\d+)""")
                val matches = pattern.findAll(output).toList()
                MirrordLogger.logger.info(
                    "findPidByJdwpPort: clientMode — LISTENING matches=${matches.size}"
                )
                matches.firstOrNull()?.groupValues?.get(1)?.toLongOrNull()
            }

            MirrordLogger.logger.info(
                "findPidByJdwpPort: RESULT port=$port debuggerListens=$debuggerListens → pid=$pid"
            )
            pid
        } catch (e: Exception) {
            MirrordLogger.logger.warn("findPidByJdwpPort: failed for port $port: ${e.message}", e)
            null
        }
    }

    /**
     * Remove mirrord env leftovers from the external system configurations.
     */
    override fun attachToProcess(
        configuration: RunConfigurationBase<*>,
        handler: ProcessHandler,
        runnerSettings: RunnerSettings?
    ) {
        if (configuration.isExternalBuildSystem()) {
            val envsToRestore = runningProcessEnvs.remove(configuration) ?: return
            val scriptParamsToRestore = runningProcessScriptParams.remove(configuration)

            handler.addProcessListener(object : ProcessListener {
                override fun processTerminated(event: ProcessEvent) {
                    configuration.settings.env.apply {
                        clear()
                        putAll(envsToRestore)
                    }
                    configuration.settings.scriptParameters = scriptParamsToRestore // null if key wasn't present
                }

                override fun startNotified(event: ProcessEvent) {}

                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {}
            })
        }
    }
}

@file:Suppress("UnstableApiUsage")

package com.metalbear.mirrord.products.idea

import com.intellij.debugger.engine.DebugProcess
import com.intellij.debugger.engine.DebugProcessImpl
import com.intellij.debugger.engine.DebugProcessListener
import com.intellij.debugger.engine.JavaDebugProcess
import com.intellij.debugger.engine.events.DebuggerCommandImpl
import com.intellij.debugger.impl.GenericDebuggerRunnerSettings
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
import com.intellij.openapi.util.SystemInfo
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerManagerListener
import com.metalbear.mirrord.CONFIG_ENV_NAME
import com.metalbear.mirrord.MirrordBinaryManager
import java.io.File
import com.metalbear.mirrord.MirrordLogger
import com.metalbear.mirrord.MirrordPitm
import com.metalbear.mirrord.MirrordProjectService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class IdeaRunConfigurationExtension : RunConfigurationExtension() {
    /**
     * mirrord env set in ExternalRunConfigurations. Used for cleanup the configuration after the execution has ended.
     */
    private val runningProcessEnvs = ConcurrentHashMap<Project, Map<String, String>>()
    private val runningProcessScriptParams = ConcurrentHashMap<Project, String>()

    override fun isApplicableFor(configuration: RunConfigurationBase<*>): Boolean {
        val applicable = !configuration.name.startsWith("Build ") && !configuration.name.startsWith("Tomcat")

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

    private fun <T : RunConfigurationBase<*>> getMirrordConfigPath(configuration: T, params: JavaParameters): String? {
        return params.env[CONFIG_ENV_NAME] ?: if (configuration is ExternalSystemRunConfiguration) {
            val ext = configuration as ExternalSystemRunConfiguration
            ext.settings.env[CONFIG_ENV_NAME]
        } else {
            null
        }
    }

    override fun <T : RunConfigurationBase<*>> updateJavaParameters(
        configuration: T,
        params: JavaParameters,
        runnerSettings: RunnerSettings?
    ) {
        val service = configuration.project.service<MirrordProjectService>()

        MirrordLogger.logger.debug("wsl check")
        val wsl = when (val request = createEnvironmentRequest(configuration, configuration.project)) {
            is WslTargetEnvironmentRequest -> request.configuration.distribution!!
            else -> null
        }

        val extraEnv = if (configuration is ExternalSystemRunConfiguration) {
            val extraEnv = params.env + configuration.settings.env
            extraEnv
        } else {
            params.env
        }

        service.execManager.wrapper("idea", extraEnv).apply {
            this.wsl = wsl
        }.start()?.let { executionInfo ->
            val mirrordEnv = executionInfo.environment + mapOf(Pair("MIRRORD_DETECT_DEBUGGER_PORT", "javaagent"))
            val envToUnset = executionInfo.envToUnset

            // On Windows native, try to wrap the JDK with mirrord pitm. When that
            // succeeds, the mirrord env vars are ferried to the child via
            // MIRRORD_CHILD_ENV only — they must NOT be set on params.env directly,
            // or the mirrord.exe wrapper itself would inherit them.
            //
            // ExternalSystemRunConfiguration (Gradle/Maven) is excluded because
            // params.jdk is null — Gradle manages its own JDK. For Gradle debug
            // on Windows we use mirrord-attach instead (armIdeaDebugAttach below).
            val pitmWrapped = if (
                SystemInfo.isWindows &&
                wsl == null &&
                configuration !is ExternalSystemRunConfiguration
            ) {
                wrapJdkWithPitm(configuration.project, params, mirrordEnv, envToUnset)
            } else {
                false
            }

            if (!pitmWrapped) {
                params.env = params.env + mirrordEnv - envToUnset.orEmpty().toSet()
            }

            runningProcessEnvs[configuration.project] = params.env.toMap()

            // Gradle support (and external system configuration)
            if (configuration is ExternalSystemRunConfiguration) {
                val extConfig = configuration as ExternalSystemRunConfiguration
                runningProcessEnvs[configuration.project] = extConfig.settings.env.toMap()
                extConfig.settings.scriptParameters?.let {
                    runningProcessScriptParams[configuration.project] = it
                }

                if (SystemInfo.isWindows && wsl == null && runnerSettings !is GenericDebuggerRunnerSettings) {
                    // Windows native + Run: inject a Gradle init script that wraps
                    // JavaExec tasks with `mirrord pitm`, and ferry env vars via
                    // MIRRORD_CHILD_ENV so they only reach the child process.
                    wrapGradleRunWithPitm(extConfig, mirrordEnv, envToUnset)
                } else {
                    // Non-Windows, or Debug: set env vars directly on the config.
                    val env = extConfig.settings.env +
                        mirrordEnv -
                        envToUnset.orEmpty().toSet()
                    extConfig.settings.env = env
                }

                // Windows native + Debug: hook the JDWP ATTACHED event to hold the
                // VM suspended (via JDI suspend count) while we inject the layer.
                if (SystemInfo.isWindows && wsl == null && runnerSettings is GenericDebuggerRunnerSettings) {
                    armIdeaDebugAttach(configuration.project, mirrordEnv)
                }
            }
            MirrordLogger.logger.debug("setting env and finishing")
        }
    }

    /**
     * Replaces `params.jdk` with a fake JDK whose `bin/java.exe` is actually
     * `mirrord.exe`. This is necessary because IntelliJ builds the java command
     * line as `<jdk.homePath>/bin/java.exe <args>` — there is no other way to
     * override the executable for a `JavaParameters`-based launch.
     *
     * When IntelliJ invokes this fake `java.exe`, mirrord's `run_as_java_launcher`
     * (see `pitm.rs`) detects `argv[0]` is `java.exe` and enters pitm mode using:
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
        val realJdk = params.jdk
        if (realJdk == null) {
            MirrordLogger.logger.warn("wrapJdkWithPitm: params.jdk is null, cannot wrap")
            return false
        }
        val realHome = realJdk.homePath
        if (realHome == null) {
            MirrordLogger.logger.warn("wrapJdkWithPitm: real JDK home is null, cannot wrap")
            return false
        }

        val mirrordExe = try {
            File(service<MirrordBinaryManager>().getBinary("idea", null, project))
        } catch (e: Exception) {
            MirrordLogger.logger.warn("wrapJdkWithPitm: failed to resolve mirrord binary: ${e.message}")
            return false
        }

        val wrapped = MirrordPitmJdk.wrap(realJdk, mirrordExe) ?: return false

        val childEnvPayload = MirrordPitm.encodeChildEnv(mirrordEnvVars, envToUnset)

        params.jdk = wrapped
        params.env = params.env + mapOf(
            MirrordPitmJdk.REAL_JAVA_ENV to "$realHome/bin/java.exe",
            MirrordPitm.CHILD_ENV_VAR to childEnvPayload
        )
        MirrordLogger.logger.info(
            "wrapJdkWithPitm: JDK wrapped, real java at $realHome/bin/java.exe, " +
                "${mirrordEnvVars.size} env vars ferried via ${MirrordPitm.CHILD_ENV_VAR}"
        )
        return true
    }

    private fun resolveCliPath(project: Project): String =
        service<MirrordBinaryManager>().getCliPath("idea", null, project)

    /**
     * Creates a Gradle init script that wraps `JavaExec` tasks with `mirrord pitm`
     * for suspended-start layer injection on Windows.
     *
     * The init script clears the task's actions and replaces them with a
     * `project.exec` that calls `mirrord.exe pitm -- <real java> <jvm args>`.
     * This sidesteps `JavaExec`'s `javaLauncher` finalization, which rejects
     * a non-JDK executable. Mirrord env vars reach the child via
     * [MirrordPitm.CHILD_ENV_VAR].
     */
    private fun wrapGradleRunWithPitm(
        configuration: ExternalSystemRunConfiguration,
        mirrordEnvVars: Map<String, String>,
        envToUnset: List<String>?
    ) {
        val cliPath = resolveCliPath(configuration.project).replace("\\", "/")
        val childEnvPayload = MirrordPitm.encodeChildEnv(mirrordEnvVars, envToUnset)
        val taskFilter = gradleTaskNameFilter(configuration.settings.taskNames)

        val initScript = try {
            createPitmInitScript(cliPath, taskFilter)
        } catch (e: Exception) {
            MirrordLogger.logger.warn("wrapGradleRunWithPitm: failed to create init script: ${e.message}")
            val env = configuration.settings.env + mirrordEnvVars - envToUnset.orEmpty().toSet()
            configuration.settings.env = env
            return
        }

        configuration.settings.env = configuration.settings.env + mapOf(
            MirrordPitm.CHILD_ENV_VAR to childEnvPayload
        )
        appendInitScript(configuration, initScript)

        MirrordLogger.logger.info(
            "wrapGradleRunWithPitm: init script at ${initScript.absolutePath}, " +
                "cli=$cliPath, ${mirrordEnvVars.size} env vars in MIRRORD_CHILD_ENV"
        )
    }

    /** Builds a Groovy set literal from Gradle task names, stripping the `:` project-path prefix. */
    private fun gradleTaskNameFilter(taskNames: List<String>): String {
        val names = taskNames.map { it.removePrefix(":") }
        return names.joinToString(", ") { "'${it.replace("'", "\\'")}'" }
    }

    /**
     * Creates a temp Gradle init script that replaces matching JavaExec task
     * actions with a `project.exec` calling `mirrord pitm -- <java> <args>`.
     * This sidesteps JavaExec's `javaLauncher` finalization which rejects
     * non-JDK executables.
     */
    private fun createPitmInitScript(cliPath: String, taskFilter: String): File {
        return File.createTempFile("mirrord-pitm-", ".gradle").apply {
            deleteOnExit()
            writeText(
                """
                def mirrordTargetTasks = [${taskFilter}] as Set
                allprojects {
                    tasks.withType(JavaExec).configureEach { task ->
                        if (!mirrordTargetTasks.contains(task.name)) return
                        task.actions.clear()
                        task.doLast {
                            def realJava = task.executable
                            def cmdArgs = ['pitm', '--', realJava] + task.allJvmArgs + ['-cp', task.classpath.asPath]
                            if (task.mainClass.isPresent()) cmdArgs += task.mainClass.get()
                            cmdArgs += (task.args ?: [])
                            project.exec { spec ->
                                spec.executable '${cliPath}'
                                spec.args cmdArgs
                                spec.environment(task.environment)
                                spec.environment('${MirrordPitm.CHILD_ENV_VAR}', System.getenv('${MirrordPitm.CHILD_ENV_VAR}') ?: '')
                                spec.workingDir = task.workingDir
                                spec.standardInput = System.in
                            }
                        }
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
     * (Gradle owns JVM creation). See `attach.rs` for the CLI injection flow.
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
                                        port = conn.address?.toIntOrNull()
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
                                                    "armIdeaDebugAttach: could not find PID for JDWP port $port"
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
            val process = ProcessBuilder("cmd", "/c", "netstat -ano")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor(10, TimeUnit.SECONDS)

            val pid = if (debuggerListens) {
                // IntelliJ listens on :port → target is the side whose REMOTE addr is :port.
                // netstat: TCP  <local>:<localPort>  <remote>:<remotePort>  ESTABLISHED  <pid>
                // We want lines where REMOTE ends with :<port> but LOCAL is NOT :<port>
                // (to exclude IntelliJ's own side of the connection).
                val pattern = Regex("""\s+TCP\s+(\S+)\s+\S+:${port}\s+ESTABLISHED\s+(\d+)""")
                pattern.findAll(output)
                    .filter { !it.groupValues[1].endsWith(":$port") }
                    .firstOrNull()
                    ?.groupValues?.get(2)?.toLongOrNull()
            } else {
                // Target listens on :port → match LOCAL port, LISTENING
                val pattern = Regex("""\s+TCP\s+\S+:${port}\s+\S+\s+LISTENING\s+(\d+)""")
                pattern.find(output)?.groupValues?.get(1)?.toLongOrNull()
            }

            MirrordLogger.logger.info(
                "findPidByJdwpPort: port=$port, debuggerListens=$debuggerListens → pid=$pid"
            )
            pid
        } catch (e: Exception) {
            MirrordLogger.logger.warn("findPidByJdwpPort: failed for port $port: ${e.message}")
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
        if (configuration is ExternalSystemRunConfiguration) {
            val envsToRestore = runningProcessEnvs.remove(configuration.project) ?: return
            val scriptParamsToRestore = runningProcessScriptParams.remove(configuration.project)

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

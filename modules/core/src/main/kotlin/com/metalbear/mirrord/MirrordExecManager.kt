package com.metalbear.mirrord

import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.metalbear.mirrord.bifrost.HostPath
import com.metalbear.mirrord.bifrost.MirrordEnvironment
import com.metalbear.mirrord.bifrost.TargetPath
import java.nio.file.Files

class MirrordExecManager(private val service: MirrordProjectService) {
    /**
     * Is thrown when the progress bar dialog for listing targets, specifically during initialisation, is cancelled.
     * This is used to show a specific help popup in the case that listing targets took too long.
     */
    class InitListingTargetsCancelledException(cause: Throwable? = null) :
        ProcessCanceledException("Initial mirrord target listing was cancelled") {
        init {
            cause?.let { initCause(it) }
        }
    }

    /**
     * Attempts to show the target selection dialog and allow user to select the mirrord target.
     *
     * @return target chosen by the user
     * @throws ProcessCanceledException if the dialog cannot be displayed
     */
    private fun chooseTarget(
        cli: TargetPath,
        environment: MirrordEnvironment,
        config: TargetPath?,
        mirrordApi: MirrordApi
    ): MirrordExecDialog.UserSelection {
        MirrordLogger.logger.debug("choose target called")

        val getTargets = { namespace: String?, targetTypes: List<String> -> mirrordApi.listTargets(cli, config, environment, namespace, targetTypes) }
        val application = ApplicationManager.getApplication()

        val selected = if (application.isDispatchThread) {
            MirrordLogger.logger.debug("dispatch thread detected, choosing target on current thread")
            val dialog = try {
                MirrordExecDialog(service.project, getTargets)
            } catch (e: ProcessCanceledException) {
                throw InitListingTargetsCancelledException(e)
            }
            dialog.showAndGetSelection()
        } else if (!application.isReadAccessAllowed) {
            MirrordLogger.logger.debug("no read lock detected, choosing target on dispatch thread")
            var target: MirrordExecDialog.UserSelection? = null
            application.invokeAndWait {
                MirrordLogger.logger.debug("choosing target from invoke")
                target = MirrordExecDialog(service.project, getTargets).showAndGetSelection()
            }
            target
        } else {
            MirrordLogger.logger.debug("read lock detected, aborting target selection")

            service
                .notifier
                .notification(
                    "Due to a known IntelliJ platform limitation, " +
                        "mirrord plugin was unable to display the target selection dialog. " +
                        "You can set it manually in the configuration file.",
                    NotificationType.WARNING
                )
                .apply {
                    config.let {
                        when {
                            it != null -> withOpenPath(it.value)
                            else -> withAction("Create") { _, _ ->
                                WriteAction.run<InvalidProjectException> {
                                    val newConfig = service.configApi.createDefaultConfig()
                                    FileEditorManager.getInstance(service.project).openFile(newConfig, true)
                                }
                            }
                        }
                    }
                }
                .withLink("Config doc", "https://mirrord.dev/docs/reference/configuration/#root-target")
                .fire()

            null
        }

        return selected ?: throw ProcessCanceledException()
    }

    private fun cliPath(environment: MirrordEnvironment, product: String): TargetPath =
        service<MirrordBinaryManager>().getCliPath(product, environment, service.project)

    /**
     * Starts a plugin version check in a background thread.
     */
    private fun dispatchPluginVersionCheck() {
        MirrordLogger.logger.debug("Plugin version check triggered")

        ProgressManager.getInstance().run(object : Task.Backgroundable(service.project, "mirrord plugin version check", true) {
            override fun run(indicator: ProgressIndicator) {
                service.versionCheck.checkVersion()
            }

            override fun onThrowable(error: Throwable) {
                MirrordLogger.logger.debug("Failed to check plugin updates", error)
                service.notifier.notifySimple(
                    "Failed to check for plugin update",
                    NotificationType.WARNING
                )
            }
        })
    }

    /**
     * Resolves path to the mirrord config and the session target.
     *
     * Returns null if mirrord is disabled.
     */
    private fun prepareStart(
        environment: MirrordEnvironment,
        product: String,
        projectEnvVars: Map<String, String>?,
        mirrordApi: MirrordApi
    ): Pair<TargetPath?, MirrordExecDialog.UserSelection>? {
        MirrordLogger.logger.debug("MirrordExecManager.start")
        val mirrordActiveValue = projectEnvVars?.get("MIRRORD_ACTIVE")
        val explicitlyEnabled = mirrordActiveValue == "1"
        val explicitlyDisabled = mirrordActiveValue == "0"
        if ((!service.enabled && !explicitlyEnabled) || explicitlyDisabled) {
            MirrordLogger.logger.debug("disabled, returning")
            return null
        }

        dispatchPluginVersionCheck()

        val mirrordConfigPath = projectEnvVars?.get(CONFIG_ENV_NAME)?.let {
            if (it.contains("\$ProjectPath\$")) {
                val projectFile = service.configApi.getProjectDir()
                projectFile.canonicalPath?.let { path ->
                    it.replace("\$ProjectPath\$", path)
                } ?: run {
                    service.notifier.notifySimple(
                        "Failed to evaluate `ProjectPath` macro used in `$CONFIG_ENV_NAME` environment variable",
                        NotificationType.WARNING
                    )
                    it
                }
            } else {
                it
            }
        }
        val cli = cliPath(environment, product)

        MirrordLogger.logger.debug("MirrordExecManager.start: mirrord cli path is $cli")
        // Find the mirrord config path, then call `mirrord verify-config {path}` so we can display warnings/errors
        // from the config without relying on mirrord-layer.

        // The lookup finds the file through the IDE's own VFS, so it is a host path. It has
        // to be translated before the CLI sees it: under a dev container the CLI runs where the
        // host filesystem does not exist, and mirrord answers an unreadable config by silently
        // falling back to defaults — which is exactly how COR-1385 presented.
        val configPath = service.configApi.getConfigPath(mirrordConfigPath)?.let { raw ->
            // On-device first, remote second, the same order the custom binary path uses.
            //
            // A user is allowed to point MIRRORD_CONFIG_FILE at a path that is already valid where
            // mirrord runs — `/workspaces/app/mirrord.json` inside the container. Converting that
            // blindly is worse than doing nothing: on a Windows host `Paths.get` rewrites it with
            // backslashes, the CLI cannot read it, and mirrord falls back to built-in defaults in
            // silence. That is the COR-1385 signature this code exists to remove.
            val hostFile = runCatching { HostPath.of(raw) }.getOrNull()
            if (hostFile != null && runCatching { Files.exists(hostFile.path) }.getOrDefault(false)) {
                environment.resolve(hostFile)
            } else {
                TargetPath(raw)
            }
        }
        MirrordLogger.logger.info("mirrord.config: resolved target-side config path = ${configPath ?: "NONE (defaults will apply)"}")

        val verifiedConfig = configPath?.let {
            val verifiedConfigOutput =
                mirrordApi.verifyConfig(cli, it, environment)
            MirrordLogger.logger.debug("MirrordExecManager.start: verifiedConfigOutput: $verifiedConfigOutput")
            MirrordVerifiedConfig(verifiedConfigOutput, service.notifier).apply {
                MirrordLogger.logger.debug("MirrordExecManager.start: MirrordVerifiedConfig: $it")
                if (isError()) {
                    MirrordLogger.logger.debug("MirrordExecManager.start: invalid config error")
                    throw InvalidConfigException(it.value, "Validation failed for config")
                }
            }
        }

        MirrordLogger.logger.debug("Verified Config: $verifiedConfig, Target selection.")

        val targetSet = verifiedConfig?.let { isTargetSet(it.config) } ?: false
        val target = if (!targetSet) {
            // There is no config file or the config does not specify a target, so show dialog.
            MirrordLogger.logger.debug("target not selected, showing dialog")
            chooseTarget(cli, environment, configPath, mirrordApi)
        } else {
            MirrordExecDialog.UserSelection(null, null)
        }

        return Pair(configPath, target)
    }

    /**
     * Checks for env vars that might've been left behind by some previous execution of mirrord.
     *
     * Sometimes a crash or under weird circumstances, the IDE doesn't clear the launch config env vars of the ones we've
     * added, so this performs a check and spits out a warning to the user, even when mirrord is **disabled**!
     *
     * @param projectEnvVars Contains both system env vars, and (active) launch settings, see `Wrapper`.
     */
    @Throws(MirrordError::class)
    private fun checkForSuspiciousEnvVars(
        projectEnvVars: Map<String, String>?
    ) {
        val suspiciousMap = projectEnvVars?.filter {
            it.key == "MIRRORD_RESOLVED_CONFIG" || ((it.key == "LD_PRELOAD" || it.key == "DYLD_INSERT_LIBRARIES") && it.value.contains("libmirrord"))
        }

        if (suspiciousMap?.isEmpty() == false) {
            MirrordLogger.logger.debug("Detected env var that was probably left behind! The culprits are: $suspiciousMap")
            throw MirrordError(
                "Detected mirrord environment variables that were probably left behind by a previous execution: ${suspiciousMap.keys}!" +
                    " Please check your project launch configuration and remove environment variables that you do not recognize."
            )
        }
    }

    /**
     * Starts mirrord, shows dialog for selecting pod if target is not set and returns env to set.
     *
     * @param projectEnvVars Contains both system env vars, and (active) launch settings, see `Wrapper`.
     * @return extra environment variables to set for the executed process and path to the patched executable.
     * null if mirrord service is disabled
     * @throws ProcessCanceledException if the user cancelled
     */
    private fun start(
        environment: MirrordEnvironment,
        executable: String?,
        product: String,
        projectEnvVars: Map<String, String>?
    ): MirrordExecution? {
        checkForSuspiciousEnvVars(projectEnvVars)

        val mirrordApi = service.mirrordApi(projectEnvVars)
        val (configPath, target) = this.prepareStart(environment, product, projectEnvVars, mirrordApi) ?: return null
        val cli = cliPath(environment, product)

        val executionInfo = mirrordApi.exec(
            cli,
            target,
            configPath,
            executable,
            environment
        )
        MirrordLogger.logger.debug("MirrordExecManager.start: executionInfo: $executionInfo")

        executionInfo.environment["MIRRORD_IGNORE_DEBUGGER_PORTS"] = "35000-65535"
        // Verbose-logging env (when enabled in settings). Goes on executionInfo.environment so it
        // reaches the layer through every product/platform path, including the Windows
        // MIRRORD_CHILD_ENV payload.
        executionInfo.environment.putAll(
            MirrordSettingsState.instance.mirrordState.troubleshootingLayerEnvVars { hostPath ->
                // Never let a diagnostic setting stop the product from starting. `resolve` throws
                // when the target cannot see the path, and a host directory chosen in Settings is
                // not mounted into a container — so translating it strictly turned "switch on
                // verbose logs" into "every run fails". Falling back to the raw path at worst puts
                // the log somewhere unhelpful, which is what a user of this switch can act on.
                runCatching { environment.resolve(HostPath.of(hostPath)).value }
                    .getOrElse {
                        MirrordLogger.logger.warn(
                            "mirrord: troubleshooting log path '$hostPath' is not reachable from " +
                                "${environment.name}; passing it through unchanged"
                        )
                        hostPath
                    }
            }
        )
        return executionInfo
    }

    /**
     * Runs `mirrord attach <PID>`. Expects `mirrord ext` to have already
     * started the intproxy and set env vars on the target process.
     *
     * Only reached from Rider (`RiderPatchCommandLineExtension`), where the debugger — not
     * Gradle — owns process creation, so the JVM can't be pitm-wrapped. IDEA's Windows-native
     * paths (including Gradle Run and Debug) all use `mirrord pitm` instead.
     *
     * CLI source: https://github.com/metalbear-co/mirrord/blob/main/mirrord/cli/src/attach.rs
     * Introduced in: https://github.com/metalbear-co/mirrord/pull/3995
     */
    fun attach(cliPath: TargetPath, projectEnvVars: Map<String, String>, pid: Long, environment: MirrordEnvironment): MirrordAttachExecution {
        MirrordLogger.logger.info(
            "MirrordExecManager.attach: ENTER pid=$pid cliPath=$cliPath projectEnvVars=${projectEnvVars.size}"
        )
        val started = System.currentTimeMillis()
        val mirrordApi = service.mirrordApi(projectEnvVars)
        try {
            val result = mirrordApi.attach(cliPath, pid, environment)
            MirrordLogger.logger.info(
                "MirrordExecManager.attach: SUCCESS pid=$pid in ${System.currentTimeMillis() - started}ms"
            )
            return result
        } catch (e: Throwable) {
            MirrordLogger.logger.warn(
                "MirrordExecManager.attach: FAILED pid=$pid after ${System.currentTimeMillis() - started}ms: ${e.message}",
                e
            )
            throw e
        }
    }

    private fun containerStart(
        environment: MirrordEnvironment,
        product: String,
        projectEnvVars: Map<String, String>?
    ): MirrordContainerExecution? {
        val mirrordApi = service.mirrordApi(projectEnvVars)
        val (configPath, target) = this.prepareStart(environment, product, projectEnvVars, mirrordApi) ?: return null
        val cli = cliPath(environment, product)

        val executionInfo = mirrordApi.containerExec(
            cli,
            target,
            configPath,
            environment
        )
        MirrordLogger.logger.debug("MirrordExecManager.start: executionInfo: $executionInfo")

        executionInfo.extraArgs.add("-e")
        executionInfo.extraArgs.add("MIRRORD_IGNORE_DEBUGGER_PORTS=\"35000-65535\"")

        // Verbose-logging env (when enabled in settings), forwarded into the container.
        // A host-selected directory is not mounted into the container, so keep trace output on
        // stderr there instead of forwarding a path that cannot refer to the chosen directory.
        MirrordSettingsState.instance.mirrordState.troubleshootingLayerEnvVars { "" }.forEach { (key, value) ->
            executionInfo.extraArgs.add("-e")
            executionInfo.extraArgs.add("$key=$value")
        }

        return executionInfo
    }

    /**
     * Wrapper around `MirrordExecManager` that is called by each IDE, or language variant.
     *
     * Helps to handle special cases and differences between the IDEs or language runners (like npm).
     */
    class Wrapper(
        private val manager: MirrordExecManager,
        private val product: String,
        private val extraEnvVars: Map<String, String>?,
        private val environment: MirrordEnvironment
    ) {
        var executable: String? = null

        fun start(): MirrordExecution? {
            return try {
                manager.start(environment, executable, product, extraEnvVars)
            } catch (e: MirrordError) {
                e.showHelp(manager.service.project)
                throw e
            } catch (e: InitListingTargetsCancelledException) {
                manager.service.notifier.notifySimple("mirrord was cancelled: if listing targets took too long, you can specify the target in the mirrord config", NotificationType.WARNING)
                throw e
            } catch (e: ProcessCanceledException) {
                manager.service.notifier.notifySimple("mirrord was cancelled", NotificationType.WARNING)
                throw e
            } catch (e: Throwable) {
                val mirrordError = MirrordError(e.toString(), e)
                mirrordError.showHelp(manager.service.project)
                throw e
            }
        }

        fun containerStart(): MirrordContainerExecution? {
            return try {
                manager.containerStart(environment, product, extraEnvVars)
            } catch (e: MirrordError) {
                e.showHelp(manager.service.project)
                throw e
            } catch (e: ProcessCanceledException) {
                manager.service.notifier.notifySimple("mirrord was cancelled", NotificationType.WARNING)
                throw e
            } catch (e: Throwable) {
                val mirrordError = MirrordError(e.toString(), e)
                mirrordError.showHelp(manager.service.project)
                throw e
            }
        }
    }

    /**
     * Gives the caller a handle to call `MirrordExecManager::start`, based on the `product`.
     *
     * @param product The IDE/language that we're wrapping mirrord execution around, some valid
     * values are: "rider", "JS", "nodejs" (there are many more).
     *
     * @param extraEnvVars Environment variables that come from project/IDE special environment.
     *
     * @return A `Wrapper` where you may call `start` to start running mirrord.
     */
    fun wrapper(product: String, extraEnvVars: Map<String, String>?, environment: MirrordEnvironment): Wrapper {
        return Wrapper(this, product, extraEnvVars, environment)
    }
}

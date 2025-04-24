package com.metalbear.mirrord

import com.intellij.execution.wsl.WSLDistribution
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.util.SystemInfo

/**
 * Functions to be called when one of our entry points to the program is called - when process is
 * launched, when go entrypoint, etc. It will check to see if it already occurred for current run and
 * if it did, it will do nothing
 */
class MirrordExecManager(private val service: MirrordProjectService) {
    /**
     * Attempts to show the target selection dialog and allow user to select the mirrord target.
     *
     * @return target chosen by the user
     * @throws ProcessCanceledException if the dialog cannot be displayed
     */
    private fun chooseTarget(
        cli: String,
        wslDistribution: WSLDistribution?,
        config: String?,
        mirrordApi: MirrordApi
    ): MirrordExecDialog.UserSelection {
        MirrordLogger.logger.debug("choose target called")

        val getTargets = { namespace: String?, targetType: String -> mirrordApi.listTargets(cli, config, wslDistribution, namespace, targetType) }
        val application = ApplicationManager.getApplication()

        val selected = if (application.isDispatchThread) {
            MirrordLogger.logger.debug("dispatch thread detected, choosing target on current thread")
            MirrordExecDialog(service.project, getTargets).showAndGetSelection()
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
                    "mirrord plugin was unable to display the target selection dialog. You can set it manually in the configuration file.",
                    NotificationType.WARNING
                )
                .apply {
                    config.let {
                        when {
                            it != null -> withOpenPath(it)
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

    private fun cliPath(wslDistribution: WSLDistribution?, product: String): String {
        val path = service<MirrordBinaryManager>().getBinary(product, wslDistribution, service.project)
        return wslDistribution?.getWslPath(path) ?: path
    }

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
        wslDistribution: WSLDistribution?,
        product: String,
        projectEnvVars: Map<String, String>?,
        mirrordApi: MirrordApi
    ): Pair<String?, MirrordExecDialog.UserSelection>? {
        MirrordLogger.logger.debug("MirrordExecManager.start")
        val mirrordActiveValue = projectEnvVars?.get("MIRRORD_ACTIVE")
        val explicitlyEnabled = mirrordActiveValue == "1"
        val explicitlyDisabled = mirrordActiveValue == "0"
        if ((!service.enabled && !explicitlyEnabled) || explicitlyDisabled) {
            MirrordLogger.logger.debug("disabled, returning")
            return null
        }

        if (SystemInfo.isWindows && wslDistribution == null) {
            throw MirrordError("can't use on Windows without WSL")
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
        val cli = cliPath(wslDistribution, product)

        MirrordLogger.logger.debug("MirrordExecManager.start: mirrord cli path is $cli")
        // Find the mirrord config path, then call `mirrord verify-config {path}` so we can display warnings/errors
        // from the config without relying on mirrord-layer.

        val configPath = service.configApi.getConfigPath(mirrordConfigPath)
        MirrordLogger.logger.debug("MirrordExecManager.start: config path is $configPath")

        val verifiedConfig = configPath?.let {
            val verifiedConfigOutput =
                mirrordApi.verifyConfig(cli, wslDistribution?.getWslPath(it) ?: it, wslDistribution)
            MirrordLogger.logger.debug("MirrordExecManager.start: verifiedConfigOutput: $verifiedConfigOutput")
            MirrordVerifiedConfig(verifiedConfigOutput, service.notifier).apply {
                MirrordLogger.logger.debug("MirrordExecManager.start: MirrordVerifiedConfig: $it")
                if (isError()) {
                    MirrordLogger.logger.debug("MirrordExecManager.start: invalid config error")
                    throw InvalidConfigException(it, "Validation failed for config")
                }
            }
        }

        MirrordLogger.logger.debug("Verified Config: $verifiedConfig, Target selection.")

        val targetSet = verifiedConfig?.let { isTargetSet(it.config) } ?: false
        val target = if (!targetSet) {
            // There is no config file or the config does not specify a target, so show dialog.
            MirrordLogger.logger.debug("target not selected, showing dialog")
            chooseTarget(cli, wslDistribution, configPath, mirrordApi)
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
        wslDistribution: WSLDistribution?,
        executable: String?,
        product: String,
        projectEnvVars: Map<String, String>?
    ): MirrordExecution? {
        checkForSuspiciousEnvVars(projectEnvVars)

        val mirrordApi = service.mirrordApi(projectEnvVars)
        val (configPath, target) = this.prepareStart(wslDistribution, product, projectEnvVars, mirrordApi) ?: return null
        val cli = cliPath(wslDistribution, product)

        val executionInfo = mirrordApi.exec(
            cli,
            target,
            configPath,
            executable,
            wslDistribution
        )
        MirrordLogger.logger.debug("MirrordExecManager.start: executionInfo: $executionInfo")

        executionInfo.environment["MIRRORD_IGNORE_DEBUGGER_PORTS"] = "35000-65535"
        return executionInfo
    }

    private fun containerStart(
        wslDistribution: WSLDistribution?,
        product: String,
        projectEnvVars: Map<String, String>?
    ): MirrordContainerExecution? {
        val mirrordApi = service.mirrordApi(projectEnvVars)
        val (configPath, target) = this.prepareStart(wslDistribution, product, projectEnvVars, mirrordApi) ?: return null
        val cli = cliPath(wslDistribution, product)

        val executionInfo = mirrordApi.containerExec(
            cli,
            target,
            configPath,
            wslDistribution
        )
        MirrordLogger.logger.debug("MirrordExecManager.start: executionInfo: $executionInfo")

        executionInfo.extraArgs.add("-e")
        executionInfo.extraArgs.add("MIRRORD_IGNORE_DEBUGGER_PORTS=\"35000-65535\"")

        return executionInfo
    }

    /**
     * Wrapper around `MirrordExecManager` that is called by each IDE, or language variant.
     *
     * Helps to handle special cases and differences between the IDEs or language runners (like npm).
     */
    class Wrapper(private val manager: MirrordExecManager, private val product: String, private val extraEnvVars: Map<String, String>?) {
        var wsl: WSLDistribution? = null
        var executable: String? = null

        fun start(): MirrordExecution? {
            return try {
                manager.start(wsl, executable, product, extraEnvVars)
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

        fun containerStart(): MirrordContainerExecution? {
            return try {
                manager.containerStart(wsl, product, extraEnvVars)
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
    fun wrapper(product: String, extraEnvVars: Map<String, String>?): Wrapper {
        return Wrapper(this, product, extraEnvVars)
    }
}

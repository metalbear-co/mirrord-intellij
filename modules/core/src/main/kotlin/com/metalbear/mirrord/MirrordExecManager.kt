package com.metalbear.mirrord

import com.intellij.execution.wsl.WSLDistribution
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.VirtualFileManager
import java.nio.file.Path

/**
 * Functions to be called when one of our entry points to the program is called - when process is
 * launched, when go entrypoint, etc. It will check to see if it already occurred for current run and
 * if it did, it will do nothing
 */
class MirrordExecManager(private val service: MirrordProjectService) {
    /** Attempts to show the target selection dialog and allow user to select the mirrord target.
     *
     * @return target chosen by the user (or special constant for targetless mode)
     * @throws ProcessCanceledException if the dialog cannot be displayed
     */
    private fun chooseTarget(
        cli: String,
        wslDistribution: WSLDistribution?,
        config: String?
    ): String {
        MirrordLogger.logger.debug("choose target called")

        val pods = service.mirrordApi.listPods(
            cli,
            config,
            wslDistribution
        )

        val application = ApplicationManager.getApplication()

        val selected = if (application.isDispatchThread) {
            MirrordLogger.logger.debug("dispatch thread detected, choosing target on current thread")
            MirrordExecDialog.selectTargetDialog(pods)
        } else if (!application.isReadAccessAllowed) {
            MirrordLogger.logger.debug("no read lock detected, choosing target on dispatch thread")
            var target: String? = null
            application.invokeAndWait {
                MirrordLogger.logger.debug("choosing target from invoke")
                target = MirrordExecDialog.selectTargetDialog(pods)
            }
            target
        } else {
            MirrordLogger.logger.debug("read lock detected, aborting target selection")

            service
                .notifier
                .notification(
                    "mirrord plugin was unable to display the target selection dialog. " +
                        "You can set it manually in the configuration file $config.",
                    NotificationType.WARNING
                )
                .apply {
                    val configFile = try {
                        val path = Path.of(config)
                        VirtualFileManager.getInstance().findFileByNioPath(path)
                    } catch (e: Exception) {
                        MirrordLogger.logger.debug("failed to find config under path $config", e)
                        null
                    }

                    configFile?.let {
                        withOpenFile(it)
                    }
                }
                .withLink("Config doc", "https://mirrord.dev/docs/overview/configuration/#root-target")
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
     * Starts mirrord, shows dialog for selecting pod if target is not set and returns env to set.
     *
     * @return extra environment variables to set for the executed process and path to the patched executable.
     * null if mirrord service is disabled
     * @throws ProcessCanceledException if the user cancelled
     */
    private fun start(
        wslDistribution: WSLDistribution?,
        executable: String?,
        product: String,
        mirrordConfigFromEnv: String?
    ): Pair<Map<String, String>, String?>? {
        MirrordLogger.logger.info("~~~MirrordExecManager.start")
        if (!service.enabled) {
            MirrordLogger.logger.info("~~~MirrordExecManager.start: service not enabled, returning.")
            MirrordLogger.logger.debug("disabled, returning")
            return null
        }

        if (SystemInfo.isWindows && wslDistribution == null) {
            throw MirrordError("can't use on Windows without WSL")
        }

        MirrordLogger.logger.info("~~~MirrordExecManager.start: checking version.")
        MirrordLogger.logger.debug("version check trigger")
        service.versionCheck.checkVersion() // TODO makes an HTTP request, move to background

        val cli = cliPath(wslDistribution, product)
        MirrordLogger.logger.info("~~~MirrordExecManager.start: mirrord cli path is $cli")
        // Find the mirrord config path, then call `mirrord verify-config {path}` so we can display warnings/errors
        // from the config without relying on mirrord-layer.
        val configPath = service.configApi.getConfigPath(mirrordConfigFromEnv)
        MirrordLogger.logger.info("~~~MirrordExecManager.start: config path is $cli")

        val verifiedConfig = configPath?.let {
            val verifiedConfigOutput =
                service.mirrordApi.verifyConfig(cli, wslDistribution?.getWslPath(it) ?: it, wslDistribution)
            MirrordLogger.logger.info("~~~MirrordExecManager.start: verifiedConfigOutput: $verifiedConfigOutput")
            MirrordVerifiedConfig(verifiedConfigOutput, service.notifier).apply {
                MirrordLogger.logger.info("~~~MirrordExecManager.start: MirrordVerifiedConfig: $it")
                if (isError()) {
                    MirrordLogger.logger.info("~~~MirrordExecManager.start: invalid config error")
                    throw InvalidConfigException(it, "Validation failed for config")
                }
            }
        }

        MirrordLogger.logger.info("Verified Config: $verifiedConfig, Target selection.")

        val target = if (configPath != null && !isTargetSet(verifiedConfig?.config)) {
            MirrordLogger.logger.info("target not selected, showing dialog")
            chooseTarget(cli, wslDistribution, configPath).also {
                if (it == MirrordExecDialog.targetlessTargetName) {
                    MirrordLogger.logger.info("No target specified - running targetless")
                    service.notifier.notification(
                        "No target specified, mirrord running targetless.",
                        NotificationType.INFORMATION
                    )
                        .withDontShowAgain(MirrordSettingsState.NotificationId.RUNNING_TARGETLESS)
                        .fire()
                }
            }
        } else {
            null
        }
        MirrordLogger.logger.info("~~~MirrordExecManager.start: exec")

        val executionInfo = service.mirrordApi.exec(
            cli,
            target,
            configPath,
            executable,
            wslDistribution
        )
        MirrordLogger.logger.info("~~~MirrordExecManager.start: executionInfo: $executionInfo")
        MirrordLogger.logger.info("~~~MirrordExecManager.start: patchedPath: ${executionInfo.patchedPath}")

        executionInfo.environment["MIRRORD_IGNORE_DEBUGGER_PORTS"] = "35000-65535"
        return Pair(executionInfo.environment, executionInfo.patchedPath)
    }

    class Wrapper(private val manager: MirrordExecManager, private val product: String) {
        var wsl: WSLDistribution? = null
        var executable: String? = null
        var configFromEnv: String? = null

        fun start(): Pair<Map<String, String>, String?>? {
            return try {
                manager.start(wsl, executable, product, configFromEnv)
            } catch (e: MirrordError) {
                MirrordLogger.logger.info("~~~MirrordError: ", e)
                e.showHelp(manager.service.project)
                throw e
            } catch (e: ProcessCanceledException) {
                MirrordLogger.logger.info("~~~ProcessCanceledException: ", e)
                manager.service.notifier.notifySimple("mirrord was cancelled", NotificationType.WARNING)
                throw e
            } catch (e: Throwable) {
                MirrordLogger.logger.info("~~~Throwable: ", e)
                val mirrordError = MirrordError(e.toString(), e)
                MirrordLogger.logger.info("~~~MirrordError from Throwable: ", mirrordError)
                mirrordError.showHelp(manager.service.project)
                throw e
            }
        }
    }

    fun wrapper(product: String): Wrapper {
        return Wrapper(this, product)
    }
}

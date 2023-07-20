package com.metalbear.mirrord

import com.intellij.execution.wsl.WSLDistribution
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import kotlinx.collections.immutable.toImmutableMap
import java.nio.file.Path

/**
 * Functions to be called when one of our entry points to the program is called - when process is
 * launched, when go entrypoint, etc. It will check to see if it already occurred for current run and
 * if it did, it will do nothing
 */
class MirrordExecManager(private val service: MirrordProjectService) {
    /** Attempts to show the target selection dialog and allow user to select the mirrord target.
     * If the dialog cannot be safely displayed (platform threading rules) returns null.
     *
     * @return target chosen by the user (or special constant for targetless mofr)
     * or null if the user cancelled or the dialog could not be displayed
     */
    private fun chooseTarget(
        cli: String,
        wslDistribution: WSLDistribution?,
        config: String
    ): String? {
        MirrordLogger.logger.debug("choose target called")

        val pods = service.mirrordApi.listPods(
            cli,
            config,
            wslDistribution
        ) ?: return null

        val application = ApplicationManager.getApplication()

        return if (application.isDispatchThread) {
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

            return null
        }
    }

    private fun cliPath(wslDistribution: WSLDistribution?, product: String): String? {
        val path = try {
            service.binaryManager.getBinary(product, wslDistribution)
        } catch (e: Exception) {
            service.notifier.notifyRichError("failed to fetch mirrord binary: ${e.message}")
            return null
        }
        wslDistribution?.let {
            return it.getWslPath(path)!!
        }
        return path
    }

    private fun getConfigPath(configFromEnv: String?): String? {
        return if (ApplicationManager.getApplication().isDispatchThread) {
            // We can create a file via VFS from the dispatch thread.
            WriteAction.compute<String, InvalidProjectException> {
                service.configApi.getConfigPath(configFromEnv, true)
            }
        } else if (ApplicationManager.getApplication().isReadAccessAllowed) {
            // This thread already holds a read lock, and it's not a dispatch thread.
            // There is no way to create a file via VFS here.
            // We abort if the config does not yet exist.
            service.configApi.getConfigPath(configFromEnv, false)
        } else {
            // This thread does not hold any lock,
            // we can safely wait for the config to be created in the dispatch thread.
            var config: Pair<String?, InvalidProjectException?> = Pair(null, null)

            ApplicationManager.getApplication().invokeAndWait {
                config = try {
                    val path = service.configApi.getConfigPath(configFromEnv, true)
                    Pair(path, null)
                } catch (e: InvalidProjectException) {
                    Pair(null, e)
                }
            }

            config.second?.let { throw it }
            config.first
        }
    }

    fun start(
        wslDistribution: WSLDistribution?,
        product: String,
        mirrordConfigFromEnv: String?
    ): Map<String, String>? {
        return start(wslDistribution, null, product, mirrordConfigFromEnv)?.first
    }

    /** Starts mirrord, shows dialog for selecting pod if target not set and returns env to set. */
    fun start(
        wslDistribution: WSLDistribution?,
        executable: String?,
        product: String,
        mirrordConfigFromEnv: String?
    ): Pair<Map<String, String>, String?>? {
        if (!service.enabled) {
            MirrordLogger.logger.debug("disabled, returning")
            return null
        }
        if (SystemInfo.isWindows && wslDistribution == null) {
            service.notifier.notifyRichError("Can't use mirrord on Windows without WSL")
            return null
        }

        MirrordLogger.logger.debug("version check trigger")
        service.versionCheck.checkVersion()

        val cli = this.cliPath(wslDistribution, product) ?: return null
        val config = try {
            getConfigPath(mirrordConfigFromEnv)
        } catch (e: InvalidProjectException) {
            service.notifier.notifyRichError(e.message)
            return null
        }

        if (config == null) {
            service
                .notifier
                .notification("mirrord requires configuration", NotificationType.WARNING)
                .withAction("Create default") { e, n ->
                    e
                        .project
                        ?.service<MirrordProjectService>()
                        ?.let {
                            val newConfig = WriteAction.compute<VirtualFile, InvalidProjectException> {
                                service.configApi.createDefaultConfig()
                            }
                            FileEditorManager.getInstance(service.project).openFile(newConfig, true)
                        }
                    n.expire()
                }
                .fire()

            return null
        }

        MirrordLogger.logger.debug("target selection")
        var target: String? = null

        val isTargetSet = try {
            isTargetSet(config)
        } catch (e: InvalidConfigException) {
            service.notifier.notifyRichError(e.message)
            return null
        }

        if (!isTargetSet) {
            MirrordLogger.logger.debug("target not selected, showing dialog")
            target = chooseTarget(cli, wslDistribution, config)
            if (target == null) {
                MirrordLogger.logger.warn("mirrord loading canceled")
                service.notifier.notifySimple("mirrord loading canceled.", NotificationType.WARNING)
                return null
            }
            if (target == MirrordExecDialog.targetlessTargetName) {
                MirrordLogger.logger.info("No target specified - running targetless")
                service.notifier.notification(
                    "No target specified, mirrord running targetless.",
                    NotificationType.INFORMATION
                )
                    .withDontShowAgain(MirrordSettingsState.NotificationId.RUNNING_TARGETLESS)
                    .fire()
                target = null
            }
        }

        val executionInfo = service.mirrordApi.exec(
            cli,
            target,
            config,
            executable,
            wslDistribution
        )

        executionInfo?.let {
            executionInfo.environment["MIRRORD_IGNORE_DEBUGGER_PORTS"] = "35000-65535"
            return Pair(executionInfo.environment.toImmutableMap(), executionInfo.patchedPath)
        }
        return null
    }
}

package com.metalbear.mirrord

import com.intellij.execution.wsl.WSLDistribution
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.SystemInfo
import kotlinx.collections.immutable.toImmutableMap
import java.util.concurrent.CompletableFuture

/**
 * Functions to be called when one of our entry points to the program is called - when process is
 * launched, when go entrypoint, etc. It will check to see if it already occurred for current run and
 * if it did, it will do nothing
 */
class MirrordExecManager(private val service: MirrordProjectService) {
    /** returns null if the user closed or cancelled target selection, otherwise the chosen target, which is either a
     * pod or the targetless target
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
        // In some cases, we're executing from a `ReadAction` context, which means we
        // can't block and wait for a WriteAction (such as invokeAndWait).
        // Executing it in a thread pool seems to fix :)
        // Update: We found out that if we're on DispatchThread we can just
        // run our function, and if we don't we get into a deadlock.
        // We have yet come to understand what exactly is going on, which is slightly annoying.
        return if (application.isDispatchThread) {
            MirrordLogger.logger.debug("choosing target on current thread")
            MirrordExecDialog.selectTargetDialog(pods)
        } else {
            MirrordLogger.logger.debug("choosing target on pooled thread")
            var target: String? = null
            application.executeOnPooledThread {
                application.invokeAndWait {
                    MirrordLogger.logger.debug("choosing target from invoke")
                    target = MirrordExecDialog.selectTargetDialog(pods)
                }
            }.get()
            target
        }
    }

    private fun cliPath(wslDistribution: WSLDistribution?, product: String): String? {
        val path = try {
            service.binaryManager.getBinary(product, wslDistribution)
        } catch (e: RuntimeException) {
            service.notifier.notifyRichError("failed to fetch mirrord binary: ${e.message}")
            return null
        }
        wslDistribution?.let {
            return it.getWslPath(path)!!
        }
        return path
    }

    private fun getConfigPath(configFromEnv: String?): String {
        return if (ApplicationManager.getApplication().isDispatchThread) {
            service.configApi.getConfigPath(configFromEnv)
        } else {
            val config = CompletableFuture<Pair<String?, InvalidProjectException?>>()

            ApplicationManager.getApplication().invokeLater {
                try {
                    val path = service.configApi.getConfigPath(configFromEnv)
                    config.complete(Pair(path, null))
                } catch (e: InvalidProjectException) {
                    config.complete(Pair(null, e))
                }
            }

            val result = config.get()
            result.second?.let { throw it }
            result.first!!
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

package com.metalbear.mirrord

import com.intellij.execution.wsl.WSLDistribution
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import kotlinx.collections.immutable.toImmutableMap

/**
 * Functions to be called when one of our entry points to the program is called - when process is
 * launched, when go entrypoint, etc. It will check to see if it already occurred for current run and
 * if it did, it will do nothing
 */
object MirrordExecManager {
    /** returns null if the user closed or cancelled target selection, otherwise the chosen target, which is either a
     * pod or the targetless target
     */
    private fun chooseTarget(
            cli: String,
            wslDistribution: WSLDistribution?,
            project: Project,
            config: String,
    ): String? {
        MirrordLogger.logger.debug("choose target called")

        val pods = MirrordApi.listPods(
                cli,
                config,
                project,
                wslDistribution,
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

    private fun cliPath(wslDistribution: WSLDistribution?, project: Project, product: String): String? {
        val path = try {
            MirrordBinaryManager.getBinary(project, product, wslDistribution)
        } catch (e: RuntimeException) {
            MirrordNotifier.errorNotification("failed to fetch mirrord binary: ${e.message}", project)
            return null
        }
        wslDistribution?.let {
            return it.getWslPath(path)!!
        }
        return path
    }

    fun start(
            wslDistribution: WSLDistribution?,
            project: Project,
            product: String,
            mirrordConfigFromEnv: String?
    ): Map<String, String>? {
        return start(wslDistribution, project, null, product, mirrordConfigFromEnv)?.first
    }

    /** Starts mirrord, shows dialog for selecting pod if target not set and returns env to set. */
    fun start(
            wslDistribution: WSLDistribution?,
            project: Project,
            executable: String?,
            product: String,
            mirrordConfigFromEnv: String?,
    ): Pair<Map<String, String>, String?>? {
        if (!MirrordEnabler.enabled) {
            MirrordLogger.logger.debug("disabled, returning")
            return null
        }
        if (SystemInfo.isWindows && wslDistribution == null) {
            MirrordNotifier.errorNotification("Can't use mirrord on Windows without WSL", project)
            return null
        }

        MirrordLogger.logger.debug("version check trigger")
        MirrordVersionCheck.checkVersion(project)

        val cli = this.cliPath(wslDistribution, project, product) ?: return null
        val config = try {
            MirrordConfigAPI.getConfigPath(project, mirrordConfigFromEnv)
        } catch (e: MirrordConfigAPI.InvalidProjectException) {
            MirrordNotifier.errorNotification(e.message!!, project)
            return null
        }

        MirrordLogger.logger.debug("target selection")
        var target: String? = null

        val isTargetSet = try {
            MirrordConfigAPI.isTargetSet(config)
        } catch (e: MirrordConfigAPI.InvalidConfigException) {
            MirrordNotifier.errorNotification(e.message!!, project)
            return null
        }

        if (!isTargetSet) {
            MirrordLogger.logger.debug("target not selected, showing dialog")
            target = chooseTarget(cli, wslDistribution, project, config)
            if (target == null) {
                MirrordLogger.logger.warn("mirrord loading canceled")
                MirrordNotifier.notify("mirrord loading canceled.", NotificationType.WARNING, project)
                return null
            }
            if (target == MirrordExecDialog.targetlessTargetName) {
                MirrordLogger.logger.info("No target specified - running targetless")
                MirrordNotifier.notify("No target specified, mirrord running targetless.", NotificationType.INFORMATION, project)
                target = null
            }
        }

        val executionInfo = MirrordApi.exec(
                cli,
                target,
                config,
                executable,
                project,
                wslDistribution
        )

        executionInfo?.let {
            executionInfo.environment["MIRRORD_IGNORE_DEBUGGER_PORTS"] = "45000-65535"
            return Pair(executionInfo.environment.toImmutableMap(), executionInfo.patchedPath)
        }
        return null
    }
}

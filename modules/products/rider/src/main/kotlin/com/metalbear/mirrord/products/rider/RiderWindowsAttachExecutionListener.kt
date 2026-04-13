package com.metalbear.mirrord.products.rider

import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.util.SystemInfo
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebuggerManagerListener
import com.jetbrains.rider.debugger.DotNetDebugProcess
import com.metalbear.mirrord.MirrordLogger
import com.metalbear.mirrord.MirrordProjectService

/**
 * Listens for debug sessions starting in Rider.
 *
 * In debug mode the process that the IDE launches is JetBrains.Debugger.Worker.exe,
 * NOT the user's target process.  The real target PID is reported asynchronously
 * via [DotNetDebugProcess.sessionInfo] once the DebuggerWorker connects to it.
 *
 * This listener subscribes to that reactive property and calls `mirrord attach`
 * with the correct PID.
 */
class RiderDebugAttachListener : XDebuggerManagerListener {

    override fun processStarted(debugProcess: XDebugProcess) {
        MirrordLogger.logger.info(
            "RiderDebugAttachListener: processStarted, " +
                "processType=${debugProcess::class.qualifiedName}, " +
                "isWindows=${ SystemInfo.isWindows}, " +
                "pendingAttach=${WindowsAttachState.pendingAttach != null}"
        )

        if (debugProcess !is DotNetDebugProcess) {
            MirrordLogger.logger.debug("RiderDebugAttachListener: not a DotNetDebugProcess, skipping")
            return
        }

        if (!SystemInfo.isWindows) {
            MirrordLogger.logger.debug("RiderDebugAttachListener: not Windows, skipping")
            return
        }

        val attach = WindowsAttachState.pendingAttach
        if (attach == null) {
            MirrordLogger.logger.debug("RiderDebugAttachListener: no pending attach, skipping")
            return
        }

        val project = debugProcess.session.project
        MirrordLogger.logger.info("RiderDebugAttachListener: subscribing to sessionInfo for target PID")

        debugProcess.sessionInfo.advise(debugProcess.sessionLifetime) { sessionInfo ->
            MirrordLogger.logger.info(
                "RiderDebugAttachListener: sessionInfo callback fired, processId=${sessionInfo.processId}"
            )

            // Only consume the pending attach once.
            val pending = WindowsAttachState.pendingAttach ?: run {
                MirrordLogger.logger.debug("RiderDebugAttachListener: pendingAttach already consumed, ignoring")
                return@advise
            }
            WindowsAttachState.pendingAttach = null

            val pid = sessionInfo.processId.toLong()
            MirrordLogger.logger.info("RiderDebugAttachListener: got target process pid $pid, attaching mirrord")

            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    val projectService = project.service<MirrordProjectService>()
                    projectService.execManager.attach(pending.cliPath, pending.projectEnvVars, pid)
                    MirrordLogger.logger.info("RiderDebugAttachListener: successfully attached mirrord to process $pid")
                } catch (e: Exception) {
                    MirrordLogger.logger.error("RiderDebugAttachListener: attach failed for pid $pid", e)
                    project.service<MirrordProjectService>().notifier.notifySimple(
                        "mirrord attach failed: ${e.message}",
                        NotificationType.ERROR
                    )
                }
            }
        }
    }
}

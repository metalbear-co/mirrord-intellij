package com.metalbear.mirrord.products.rider

import com.intellij.execution.RunManager
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessInfo
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.target.createEnvironmentRequest
import com.intellij.execution.wsl.target.WslTargetEnvironmentRequest
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SystemInfo
import com.jetbrains.rd.util.lifetime.Lifetime
import com.jetbrains.rider.run.PatchCommandLineExtension
import com.jetbrains.rider.run.WorkerRunInfo
import com.jetbrains.rider.runtime.DotNetRuntime
import com.metalbear.mirrord.MirrordBinaryManager
import com.metalbear.mirrord.MirrordLogger
import com.metalbear.mirrord.MirrordProjectService
import org.jetbrains.concurrency.Promise
import org.jetbrains.concurrency.resolvedPromise

/**
 * Lightweight state for the Windows native attach flow.
 *
 * `mirrord ext` (via `wrapper.start()`) has already started the intproxy and
 * set env vars on the command line. This just holds the CLI path needed for
 * `mirrord attach <PID>` to inject the layer DLL.
 */
data class WindowsAttachInfo(
    val cliPath: String,
    val projectEnvVars: Map<String, String>
)

object WindowsAttachState {
    var pendingAttach: WindowsAttachInfo? = null
}

class RiderPatchCommandLineExtension : PatchCommandLineExtension {
    private fun patchCommandLine(commandLine: GeneralCommandLine, project: Project) {
        val service = project.service<MirrordProjectService>()

        val wsl = RunManager.getInstance(project).selectedConfiguration?.configuration?.let {
            @Suppress("UnstableApiUsage")
            when (val request = createEnvironmentRequest(it, project)) {
                is WslTargetEnvironmentRequest -> request.configuration.distribution!!
                else -> null
            }
        }

        // Run `mirrord ext` to start intproxy and get env vars — needed for all platforms.
        // On non-Windows/WSL this is all that's needed (layer loads via LD_PRELOAD).
        // On Windows native we also need `mirrord attach <PID>` later to inject the DLL.
        val executionInfo = service.execManager.wrapper("rider", commandLine.environment).apply {
            this.wsl = wsl
        }.start()

        executionInfo?.let { info ->
            for (entry in info.environment.entries.iterator()) {
                commandLine.withEnvironment(entry.key, entry.value)
            }

            for (key in info.envToUnset.orEmpty()) {
                commandLine.environment.remove(key)
            }

            MirrordLogger.logger.info("patchCommandLine: mirrord ext env vars set on command line (${info.environment.size} vars)")
        }

        // On Windows native, store the CLI path for the later `mirrord attach <PID>` call.
        // The intproxy is already running and env vars are on the command line.
        if (SystemInfo.isWindows && wsl == null && service.enabled && executionInfo != null) {
            val cliPath = try {
                service<MirrordBinaryManager>().getBinary("rider", null, project)
            } catch (e: Exception) {
                MirrordLogger.logger.warn("Failed to resolve mirrord binary path, falling back to mirrord.exe: ${e.message}")
                "mirrord.exe"
            }

            WindowsAttachState.pendingAttach = WindowsAttachInfo(cliPath, commandLine.environment.toMap())
            MirrordLogger.logger.info("Windows native: pending attach stored, cliPath=$cliPath")
        }
    }

    override fun patchDebugCommandLine(lifetime: Lifetime, workerRunInfo: WorkerRunInfo, processInfo: ProcessInfo?, project: Project): Promise<WorkerRunInfo> {
        MirrordLogger.logger.info("RiderPatchCommandLineExtension: patchDebugCommandLine called")
        patchCommandLine(workerRunInfo.commandLine, project)
        workerRunInfo.commandLine.withEnvironment("MIRRORD_DETECT_DEBUGGER_PORT", "resharper")
        MirrordLogger.logger.info("RiderPatchCommandLineExtension: pendingAttach after patch = ${WindowsAttachState.pendingAttach != null}")
        return resolvedPromise(workerRunInfo)
    }

    override fun patchRunCommandLine(commandLine: GeneralCommandLine, dotNetRuntime: DotNetRuntime, project: Project): ProcessListener? {
        patchCommandLine(commandLine, project)

        val attach = WindowsAttachState.pendingAttach
        if (attach != null && SystemInfo.isWindows) {
            WindowsAttachState.pendingAttach = null
            MirrordLogger.logger.debug("Returning ProcessListener for Windows attach (run mode)")

            return object : ProcessListener {
                override fun startNotified(event: ProcessEvent) {
                    val handler = event.processHandler
                    if (handler is OSProcessHandler) {
                        val process = handler.process
                        try {
                            val pid = process.pid()
                            MirrordLogger.logger.info("mirrord run mode: process started with pid $pid, attaching...")

                            ApplicationManager.getApplication().executeOnPooledThread {
                                try {
                                    val projectService = project.service<MirrordProjectService>()
                                    projectService.execManager.attach(attach.cliPath, attach.projectEnvVars, pid)
                                    MirrordLogger.logger.info("mirrord run mode: successfully attached to process $pid")
                                } catch (e: Exception) {
                                    MirrordLogger.logger.error("mirrord run mode: attach failed for pid $pid", e)
                                    project.service<MirrordProjectService>().notifier.notifySimple(
                                        "mirrord attach failed: ${e.message}",
                                        NotificationType.ERROR
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            MirrordLogger.logger.error("Failed to get process PID", e)
                        }
                    }
                }

                override fun processTerminated(event: ProcessEvent) {}

                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {}
            }
        }

        return null
    }
}

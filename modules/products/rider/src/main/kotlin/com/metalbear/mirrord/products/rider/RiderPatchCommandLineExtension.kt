package com.metalbear.mirrord.products.rider

import com.intellij.execution.RunManager
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessInfo
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.target.createEnvironmentRequest
import com.intellij.execution.wsl.WSLDistribution
import com.intellij.execution.wsl.target.WslTargetEnvironmentRequest
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerManagerListener
import com.jetbrains.rd.util.lifetime.Lifetime
import com.jetbrains.rider.debugger.DotNetDebugProcess
import com.jetbrains.rider.run.PatchCommandLineExtension
import com.jetbrains.rider.run.WorkerRunInfo
import com.jetbrains.rider.runtime.DotNetRuntime
import com.metalbear.mirrord.MirrordBinaryManager
import com.metalbear.mirrord.MirrordExecution
import com.metalbear.mirrord.MirrordLogger
import com.metalbear.mirrord.MirrordPitm
import com.metalbear.mirrord.MirrordProjectService
import org.jetbrains.concurrency.Promise
import org.jetbrains.concurrency.resolvedPromise

class RiderPatchCommandLineExtension : PatchCommandLineExtension {

    private fun resolveWsl(project: Project): WSLDistribution? {
        return RunManager.getInstance(project).selectedConfiguration?.configuration?.let {
            @Suppress("UnstableApiUsage")
            when (val request = createEnvironmentRequest(it, project)) {
                is WslTargetEnvironmentRequest -> request.configuration.distribution!!
                else -> null
            }
        }
    }

    private fun resolveCliPath(project: Project): String =
        service<MirrordBinaryManager>().getCliPath("rider", null, project)

    /**
     * Runs `mirrord ext` and sets the resulting env vars on the command line.
     * Returns the execution info (env vars map, envToUnset) for the caller to
     * decide how to handle them (pitm wrapping vs. debug attach).
     */
    private fun startMirrordExt(
        commandLine: GeneralCommandLine,
        project: Project,
        wsl: WSLDistribution?
    ): MirrordExecution? {
        val service = project.service<MirrordProjectService>()

        val executionInfo = service.execManager.wrapper("rider", commandLine.environment).apply {
            this.wsl = wsl
        }.start()

        executionInfo?.let { info ->
            for (entry in info.environment.entries) {
                commandLine.withEnvironment(entry.key, entry.value)
            }
            for (key in info.envToUnset.orEmpty()) {
                commandLine.environment.remove(key)
            }
            MirrordLogger.logger.info("startMirrordExt: env vars set on command line (${info.environment.size} vars)")
        }

        return executionInfo
    }

    override fun patchDebugCommandLine(
        lifetime: Lifetime,
        workerRunInfo: WorkerRunInfo,
        processInfo: ProcessInfo?,
        project: Project
    ): Promise<WorkerRunInfo> {
        MirrordLogger.logger.info("RiderPatchCommandLineExtension: patchDebugCommandLine called")
        val wsl = resolveWsl(project)
        val executionInfo = startMirrordExt(workerRunInfo.commandLine, project, wsl)
        workerRunInfo.commandLine.withEnvironment("MIRRORD_DETECT_DEBUGGER_PORT", "resharper")

        // Debug on Windows native: pitm can't be used because JetBrains'
        // DebuggerWorker.exe owns process creation. Instead, hook `targetReady`
        // on DotNetDebuggerSessionModel
        // (fires when target is spawned + worker attached, before user code runs).
        // From there: session.pause() → sessionPaused → mirrord attach <pid> →
        // session.resume(). See attach.rs for the DLL injection flow.
        if (SystemInfo.isWindows && wsl == null && executionInfo != null) {
            armRiderTargetReadyAttach(project, workerRunInfo.commandLine.environment.toMap())
        }

        return resolvedPromise(workerRunInfo)
    }

    private fun armRiderTargetReadyAttach(project: Project, envVars: Map<String, String>) {
        val cliPath = resolveCliPath(project)
        MirrordLogger.logger.info("armRiderTargetReadyAttach: wiring listener, cliPath=$cliPath")

        val busConnection = project.messageBus.connect()
        busConnection.subscribe(
            XDebuggerManager.TOPIC,
            object : XDebuggerManagerListener {
                @Volatile
                private var wired = false

                override fun processStarted(debugProcess: XDebugProcess) {
                    if (wired) return
                    wired = true

                    val dotNetProcess = debugProcess as? DotNetDebugProcess
                    if (dotNetProcess == null) {
                        MirrordLogger.logger.warn(
                            "armRiderTargetReadyAttach: processStarted but not a DotNetDebugProcess " +
                                "(got ${debugProcess::class.qualifiedName}), unsubscribing"
                        )
                        busConnection.disconnect()
                        return
                    }

                    val session = debugProcess.session
                    MirrordLogger.logger.info("armRiderTargetReadyAttach: processStarted, wiring targetReady + sessionPaused")

                    // sessionPaused fires once we've requested a pause and the debugger
                    // has actually suspended the target. Run mirrord attach there.
                    session.addSessionListener(object : XDebugSessionListener {
                        @Volatile
                        private var consumed = false

                        override fun sessionPaused() {
                            if (consumed) return
                            consumed = true

                            val pid = dotNetProcess.sessionInfo.valueOrNull?.processId?.toLong()
                            if (pid == null) {
                                MirrordLogger.logger.warn(
                                    "armRiderTargetReadyAttach.sessionPaused: no PID on sessionInfo, skipping attach"
                                )
                                return
                            }
                            MirrordLogger.logger.info(
                                "armRiderTargetReadyAttach.sessionPaused: pid=$pid, attaching mirrord on pooled thread"
                            )

                            ApplicationManager.getApplication().executeOnPooledThread {
                                try {
                                    project.service<MirrordProjectService>()
                                        .execManager
                                        .attach(cliPath, envVars, pid)
                                    MirrordLogger.logger.info(
                                        "armRiderTargetReadyAttach: attach completed for pid $pid, resuming session"
                                    )
                                    // Explicit resume after synchronous attach. `attach`
                                    // only returns once the layer has signalled init, so
                                    // by this point the mirrord DLL is loaded in the target
                                    // and user code is safe to run under it.
                                    session.resume()
                                } catch (e: Exception) {
                                    // Attach failed — don't resume. Leaving the session
                                    // paused so the user can inspect state and the error.
                                    // Surfacing via notifier keeps parity with the old flow.
                                    MirrordLogger.logger.error(
                                        "armRiderTargetReadyAttach: attach failed for pid $pid, session left paused",
                                        e
                                    )
                                    project.service<MirrordProjectService>().notifier.notifySimple(
                                        "mirrord attach failed: ${e.message}",
                                        com.intellij.notification.NotificationType.ERROR
                                    )
                                }
                            }
                        }

                        override fun sessionStopped() {
                            busConnection.disconnect()
                        }
                    })

                    // targetReady fires from the Rider backend when the target process
                    // is spawned and the worker is attached. Request a pause immediately.
                    dotNetProcess.sessionProxy.targetReady.advise(dotNetProcess.sessionLifetime) { sessionInfo ->
                        MirrordLogger.logger.info(
                            "armRiderTargetReadyAttach.targetReady: pid=${sessionInfo.processId}, requesting session.pause()"
                        )
                        session.pause()
                    }
                }
            }
        )
    }

    override fun patchRunCommandLine(
        commandLine: GeneralCommandLine,
        dotNetRuntime: DotNetRuntime,
        project: Project
    ): ProcessListener? {
        MirrordLogger.logger.info("RiderPatchCommandLineExtension: patchRunCommandLine called")
        val wsl = resolveWsl(project)
        val executionInfo = startMirrordExt(commandLine, project, wsl) ?: return null

        // On Windows native, wrap with `mirrord pitm` for zero-race DLL injection.
        // The process' execution is proxied, suspended, layer injected, then resumed.
        if (SystemInfo.isWindows && wsl == null) {
            val cliPath = resolveCliPath(project)
            MirrordPitm.wrapCommandLine(commandLine, cliPath, executionInfo.environment, executionInfo.envToUnset)
        }

        return null
    }
}

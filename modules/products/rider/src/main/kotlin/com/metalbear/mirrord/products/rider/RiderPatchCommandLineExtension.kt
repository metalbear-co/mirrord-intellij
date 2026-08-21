package com.metalbear.mirrord.products.rider

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessInfo
import com.intellij.execution.process.ProcessListener
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
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
import com.metalbear.mirrord.bifrost.MirrordEnvironment
import com.metalbear.mirrord.bifrost.MirrordEnvironments
import com.metalbear.mirrord.bifrost.TargetPath
import com.metalbear.mirrord.isWinNative
import org.jetbrains.concurrency.Promise
import org.jetbrains.concurrency.resolvedPromise

class RiderPatchCommandLineExtension : PatchCommandLineExtension {

    /**
     * Rider's extension point hands us a [WorkerRunInfo] and a [Project], never the run
     * configuration being launched — which is why the old code reached for
     * `RunManager.selectedConfiguration` and read whatever happened to be selected in the
     * dropdown, right or not. Asking the project where it lives has no such failure mode.
     */
    private fun resolveEnvironment(project: Project): MirrordEnvironment =
        MirrordEnvironments.forProject(project)

    private fun resolveCliPath(project: Project, environment: MirrordEnvironment): TargetPath =
        service<MirrordBinaryManager>().getCliPath("rider", environment, project)

    /**
     * Runs `mirrord ext` and sets the resulting env vars on the command line.
     * Returns the execution info (env vars map, envToUnset) for the caller to
     * decide how to handle them (pitm wrapping vs. debug attach).
     */
    private fun startMirrordExt(
        commandLine: GeneralCommandLine,
        project: Project,
        environment: MirrordEnvironment
    ): MirrordExecution? {
        val service = project.service<MirrordProjectService>()

        val executionInfo = service.execManager.wrapper("rider", commandLine.environment, environment).start()

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
        MirrordLogger.logger.info(
            "RiderPatchCommandLineExtension.patchDebugCommandLine: ENTER exe=${workerRunInfo.commandLine.exePath} " +
                "argsCount=${workerRunInfo.commandLine.parametersList.list.size}"
        )
        val environment = resolveEnvironment(project)
        MirrordLogger.logger.info("patchDebugCommandLine: env=${environment.name}")
        val executionInfo = startMirrordExt(workerRunInfo.commandLine, project, environment)
        workerRunInfo.commandLine.withEnvironment("MIRRORD_DETECT_DEBUGGER_PORT", "resharper")

        val winNative = environment.platform().isWinNative
        MirrordLogger.logger.info(
            "patchDebugCommandLine: decision winNative=$winNative executionInfoPresent=${executionInfo != null}"
        )

        // Debug on Windows native: pitm can't be used because JetBrains'
        // DebuggerWorker.exe owns process creation. Instead, hook `targetReady`
        // on DotNetDebuggerSessionModel (fires when target is spawned + worker
        // attached, before user code runs). From there: session.pause() →
        // sessionPaused → mirrord attach <pid> → session.resume() (dispatched
        // on EDT). The pause is required to close the targetReady→attach race
        // window (CLR startup threads could otherwise issue syscalls before
        // the layer is injected). The resume must run on EDT because
        // XDebugSession fires EDT-asserting listeners during resume.
        // CLI injection flow:
        // https://github.com/metalbear-co/mirrord/blob/main/mirrord/cli/src/attach.rs
        if (winNative && executionInfo != null) {
            armRiderTargetReadyAttach(project, lifetime, workerRunInfo.commandLine.environment.toMap(), environment)
        } else {
            MirrordLogger.logger.info("patchDebugCommandLine: skipping armRiderTargetReadyAttach (not Windows-native or no executionInfo)")
        }

        MirrordLogger.logger.info("patchDebugCommandLine: EXIT")
        return resolvedPromise(workerRunInfo)
    }

    private fun armRiderTargetReadyAttach(
        project: Project,
        launchLifetime: Lifetime,
        envVars: Map<String, String>,
        environment: MirrordEnvironment
    ) {
        val cliPath = resolveCliPath(project, environment)
        MirrordLogger.logger.info("armRiderTargetReadyAttach: wiring listener, cliPath=$cliPath")

        // connect(project) + lifetime.onTermination gives belt-and-suspenders
        // cleanup: project close bounds the listener absolutely, and the
        // Rider launch lifetime disposes it as soon as THIS launch ends
        // (success, cancel, or failure before processStarted ever fires).
        val busConnection = project.messageBus.connect(project)
        launchLifetime.onTermination { busConnection.disconnect() }

        busConnection.subscribe(
            XDebuggerManager.TOPIC,
            object : XDebuggerManagerListener {
                @Volatile
                private var wired = false

                override fun processStarted(debugProcess: XDebugProcess) {
                    if (wired) return

                    // processStarted fires for every debug session in the
                    // project. Ignore sessions that aren't DotNet (e.g. a
                    // concurrent Java/Python debug) without unsubscribing —
                    // otherwise an unrelated session starting first would
                    // consume our one-shot wiring and silently skip mirrord
                    // on the target.
                    val dotNetProcess = debugProcess as? DotNetDebugProcess ?: return
                    wired = true

                    val session = debugProcess.session
                    MirrordLogger.logger.info("armRiderTargetReadyAttach: processStarted, wiring targetReady + sessionPaused")

                    // sessionPaused fires once we've requested a pause and the debugger
                    // has actually suspended the target. Run mirrord attach there so the
                    // target is frozen.
                    session.addSessionListener(object : XDebugSessionListener {
                        @Volatile
                        private var consumed = false

                        override fun sessionPaused() {
                            if (consumed) return
                            consumed = true

                            val sessionInfoNow = dotNetProcess.sessionInfo.valueOrNull
                            val pid = sessionInfoNow?.processId?.toLong()
                            if (pid == null) {
                                MirrordLogger.logger.warn(
                                    "armRiderTargetReadyAttach.sessionPaused: no PID on sessionInfo (sessionInfo=$sessionInfoNow), " +
                                        "skipping attach. Debug session will proceed WITHOUT mirrord layer."
                                )
                                project.service<MirrordProjectService>().notifier.notifySimple(
                                    "mirrord: could not determine target process PID; Rider debug proceeding without layer",
                                    NotificationType.WARNING
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
                                        .attach(cliPath, envVars, pid, environment)
                                    MirrordLogger.logger.info(
                                        "armRiderTargetReadyAttach: attach completed for pid $pid, dispatching session.resume() on EDT"
                                    )
                                    // session.resume() must run on EDT — XDebugSession fires
                                    // listeners like LinqInlayDisplay.beforeSessionResume that
                                    // assertIsEdt() and throw otherwise, aborting the resume
                                    // and leaving the session paused forever.
                                    ApplicationManager.getApplication().invokeLater {
                                        try {
                                            session.resume()
                                            MirrordLogger.logger.info(
                                                "armRiderTargetReadyAttach: session.resume() completed for pid $pid"
                                            )
                                        } catch (e: Exception) {
                                            MirrordLogger.logger.error(
                                                "armRiderTargetReadyAttach: session.resume() threw on EDT for pid $pid",
                                                e
                                            )
                                        }
                                    }
                                } catch (e: Exception) {
                                    // Attach failed — don't resume. Leaving the session
                                    // paused so the user can inspect state and the error.
                                    MirrordLogger.logger.error(
                                        "armRiderTargetReadyAttach: attach failed for pid $pid, session left paused",
                                        e
                                    )
                                    project.service<MirrordProjectService>().notifier.notifySimple(
                                        "mirrord attach failed: ${e.message}",
                                        NotificationType.ERROR
                                    )
                                }
                            }
                        }

                        override fun sessionStopped() {
                            busConnection.disconnect()
                        }
                    })

                    // targetReady fires from the Rider backend when the target process
                    // is spawned and the worker is attached. Request a pause immediately
                    // so user code is frozen before mirrord attach starts injecting.
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
        MirrordLogger.logger.info(
            "RiderPatchCommandLineExtension.patchRunCommandLine: ENTER exe=${commandLine.exePath} " +
                "argsCount=${commandLine.parametersList.list.size}"
        )
        val environment = resolveEnvironment(project)
        MirrordLogger.logger.info("patchRunCommandLine: env=${environment.name}")
        val executionInfo = startMirrordExt(commandLine, project, environment) ?: run {
            MirrordLogger.logger.info("patchRunCommandLine: startMirrordExt returned null — mirrord disabled or cancelled, exiting")
            return null
        }

        val winNative = environment.platform().isWinNative
        MirrordLogger.logger.info("patchRunCommandLine: decision winNative=$winNative")

        // On Windows native, wrap with `mirrord pitm` for zero-race DLL injection.
        // The process' execution is proxied, suspended, layer injected, then resumed.
        if (winNative) {
            val cliPath = resolveCliPath(project, environment)
            MirrordLogger.logger.info("patchRunCommandLine: wrapping with pitm cliPath=$cliPath")
            MirrordPitm.wrapCommandLine(commandLine, cliPath, executionInfo.environment, executionInfo.envToUnset)
        } else {
            MirrordLogger.logger.info("patchRunCommandLine: non-Windows or WSL — skipping pitm wrap, relying on LD_PRELOAD")
        }

        MirrordLogger.logger.info("patchRunCommandLine: EXIT")
        return null
    }
}

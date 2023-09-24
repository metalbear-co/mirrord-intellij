@file:Suppress("UnstableApiUsage")

package com.metalbear.mirrord.products.rider

import com.intellij.execution.RunManager
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessInfo
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.target.createEnvironmentRequest
import com.intellij.execution.wsl.target.WslTargetEnvironmentRequest
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.jetbrains.rd.util.lifetime.Lifetime
import com.jetbrains.rider.run.PatchCommandLineExtension
import com.jetbrains.rider.run.WorkerRunInfo
import com.jetbrains.rider.runtime.DotNetRuntime
import com.metalbear.mirrord.CONFIG_ENV_NAME
import com.metalbear.mirrord.MirrordProjectService
import org.jetbrains.concurrency.Promise
import org.jetbrains.concurrency.resolvedPromise
import java.util.concurrent.ConcurrentHashMap

class RiderPatchCommandLineExtension : PatchCommandLineExtension {

    private val runningProcessEnvs = ConcurrentHashMap<Project, Map<String, String>>()

    private fun patchCommandLine(commandLine: GeneralCommandLine, workerRunInfo: WorkerRunInfo?, project: Project) {
        val service = project.service<MirrordProjectService>()

        val wsl = RunManager.getInstance(project).selectedConfiguration?.configuration?.let {
            when (val request = createEnvironmentRequest(it, project)) {
                is WslTargetEnvironmentRequest -> request.configuration.distribution!!
                else -> null
            }
        }

        service.execManager.wrapper("rider").apply {
            this.wsl = wsl
            configFromEnv = commandLine.environment[CONFIG_ENV_NAME]
        }.start()?.let { (mirrordEnv, _) ->
            runningProcessEnvs[project] = commandLine.environment
            commandLine.withEnvironment(mirrordEnv)
        }


        workerRunInfo?.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
            }

            override fun startNotified(event: ProcessEvent) {
            }

            override fun processTerminated(event: ProcessEvent) {
                val envsToRestore = runningProcessEnvs.remove(project) ?: return
                commandLine.apply {
                    environment.clear()
                    withEnvironment(envsToRestore)
                }
            }
        })
    }


    override fun patchDebugCommandLine(
        lifetime: Lifetime,
        workerRunInfo: WorkerRunInfo,
        project: Project
    ): Promise<WorkerRunInfo> {
        patchCommandLine(workerRunInfo.commandLine, workerRunInfo, project)
        workerRunInfo.commandLine.withEnvironment("MIRRORD_DETECT_DEBUGGER_PORT", "resharper")
        return resolvedPromise(workerRunInfo)
    }

    fun patchDebugCommandLine(
        lifetime: Lifetime,
        workerRunInfo: WorkerRunInfo,
        processInfo: ProcessInfo?,
        project: Project
    ): Promise<WorkerRunInfo> {
        return patchDebugCommandLine(lifetime, workerRunInfo, project)
    }

    override fun patchRunCommandLine(
        commandLine: GeneralCommandLine,
        dotNetRuntime: DotNetRuntime,
        project: Project
    ): ProcessListener? {
        patchCommandLine(commandLine, null, project)
        return null
    }
}

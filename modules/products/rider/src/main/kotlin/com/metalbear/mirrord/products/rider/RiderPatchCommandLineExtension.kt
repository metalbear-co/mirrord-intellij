package com.metalbear.mirrord.products.rider

import com.intellij.execution.RunManager
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessInfo
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.target.createEnvironmentRequest
import com.intellij.execution.wsl.target.WslTargetEnvironmentRequest
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.jetbrains.rd.util.lifetime.Lifetime
import com.jetbrains.rider.run.PatchCommandLineExtension
import com.jetbrains.rider.run.WorkerRunInfo
import com.jetbrains.rider.runtime.DotNetRuntime
import com.metalbear.mirrord.MirrordProjectService
import org.jetbrains.concurrency.Promise
import org.jetbrains.concurrency.resolvedPromise

class RiderPatchCommandLineExtension : PatchCommandLineExtension {
    @Suppress("UnstableApiUsage") // `createEnvironmentRequest`
    private fun patchCommandLine(commandLine: GeneralCommandLine, project: Project) {
        val service = project.service<MirrordProjectService>()

        val wsl = RunManager.getInstance(project).selectedConfiguration?.configuration?.let {
            when (val request = createEnvironmentRequest(it, project)) {
                is WslTargetEnvironmentRequest -> request.configuration.distribution!!
                else -> null
            }
        }

        service.execManager.wrapper("rider", commandLine.environment).apply {
            this.wsl = wsl
        }.start()?.let { executionInfo ->
            for (entry in executionInfo.environment.entries.iterator()) {
                commandLine.withEnvironment(entry.key, entry.value)
            }

            for (key in executionInfo.envToUnset.orEmpty()) {
                commandLine.environment.remove(key)
            }
        }
    }

    override fun patchDebugCommandLine(lifetime: Lifetime, workerRunInfo: WorkerRunInfo, processInfo: ProcessInfo?, project: Project): Promise<WorkerRunInfo> {
        patchCommandLine(workerRunInfo.commandLine, project)
        workerRunInfo.commandLine.withEnvironment("MIRRORD_DETECT_DEBUGGER_PORT", "resharper")
        return resolvedPromise(workerRunInfo)
    }

    override fun patchRunCommandLine(commandLine: GeneralCommandLine, dotNetRuntime: DotNetRuntime, project: Project): ProcessListener? {
        patchCommandLine(commandLine, project)
        return null
    }
}

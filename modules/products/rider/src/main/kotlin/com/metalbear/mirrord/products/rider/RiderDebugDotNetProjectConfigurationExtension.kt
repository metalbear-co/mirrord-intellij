package com.metalbear.mirrord.products.rider

import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.components.service
import com.intellij.openapi.util.SystemInfo
import com.jetbrains.rider.run.configurations.project.DotNetProjectConfigurationExtension
import com.jetbrains.rider.run.configurations.project.DotNetProjectConfigurationParameters
import com.jetbrains.rider.runtime.RiderDotNetActiveRuntimeHost
import com.metalbear.mirrord.MirrordLogger
import com.metalbear.mirrord.MirrordProjectService

class RiderDebugDotNetProjectConfigurationExtension : DotNetProjectConfigurationExtension {

    override fun canExecute(runnerId: String): Boolean {
        val result = runnerId == DefaultDebugExecutor.EXECUTOR_ID
        MirrordLogger.logger.debug("RiderDebugDotNetProjectConfigurationExtension.canExecute: runnerId=$runnerId, result=$result")
        return result
    }

    override fun executor(
        params: DotNetProjectConfigurationParameters,
        env: ExecutionEnvironment
    ): RunProfileState {
        val project = params.project
        val service = project.service<MirrordProjectService>()
        val executable = params.toDotNetExecutable()

        MirrordLogger.logger.debug("RiderDebugDotNetProjectConfigurationExtension.executor: exePath=${executable.exePath}")

        val runtimeHost = project.service<RiderDotNetActiveRuntimeHost>()
        val runtime = runtimeHost.dotNetCoreRuntime.value
            ?: runtimeHost.getCurrentClassicNetRuntime(false).runtime
            ?: error("No .NET runtime available for project ${params.projectFilePath}")

        return runtime.createDebugState(executable, env)
    }
}

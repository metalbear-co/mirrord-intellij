@file:Suppress("UnstableApiUsage")

package com.metalbear.mirrord.products.pycharm

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.target.createEnvironmentRequest
import com.intellij.execution.wsl.target.WslTargetEnvironmentRequest
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.jetbrains.python.run.AbstractPythonRunConfiguration
import com.jetbrains.python.run.PythonRunConfigurationExtension
import com.metalbear.mirrord.CONFIG_ENV_NAME
import com.metalbear.mirrord.MirrordProjectService
import java.util.concurrent.ConcurrentHashMap

class PythonRunConfigurationExtension : PythonRunConfigurationExtension() {

    private val runningProcessEnvs = ConcurrentHashMap<Project, Map<String, String>>()

    override fun isApplicableFor(configuration: AbstractPythonRunConfiguration<*>): Boolean {
        return true
    }

    override fun isEnabledFor(
        applicableConfiguration: AbstractPythonRunConfiguration<*>,
        runnerSettings: RunnerSettings?
    ): Boolean {
        return true
    }

    override fun patchCommandLine(
        configuration: AbstractPythonRunConfiguration<*>,
        runnerSettings: RunnerSettings?,
        cmdLine: GeneralCommandLine,
        runnerId: String
    ) {
        val service = configuration.project.service<MirrordProjectService>()

        val wsl = when (val request = createEnvironmentRequest(configuration, configuration.project)) {
            is WslTargetEnvironmentRequest -> request.configuration.distribution!!
            else -> null
        }

        val currentEnv = cmdLine.environment

        service.execManager.wrapper("pycharm").apply {
            this.wsl = wsl
            configFromEnv = currentEnv[CONFIG_ENV_NAME]
        }.start()?.let { (mirrordEnv, _) ->
            val currentEnv = cmdLine.environment.toMap()
            runningProcessEnvs[configuration.project] = currentEnv
            cmdLine.withEnvironment(mirrordEnv)
        }

        currentEnv["MIRRORD_DETECT_DEBUGGER_PORT"] = "pydevd"
    }

    override fun attachToProcess(
        configuration: AbstractPythonRunConfiguration<*>,
        handler: ProcessHandler,
        runnerSettings: RunnerSettings?
    ) {
        val envsToRestore = runningProcessEnvs.remove(configuration.project) ?: return

        handler.addProcessListener(object : ProcessListener {
            override fun processTerminated(event: ProcessEvent) {
                configuration.envs = envsToRestore
            }

            override fun startNotified(event: ProcessEvent) {}

            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {}
        })
    }
}

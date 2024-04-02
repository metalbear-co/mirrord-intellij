package com.metalbear.mirrord.products.pycharm

import com.intellij.execution.wsl.target.WslTargetEnvironmentRequest
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.jetbrains.python.run.AbstractPythonRunConfiguration
import com.jetbrains.python.run.PythonExecution
import com.jetbrains.python.run.PythonRunParams
import com.jetbrains.python.run.target.HelpersAwareTargetEnvironmentRequest
import com.jetbrains.python.run.target.PythonCommandLineTargetEnvironmentProvider
import com.metalbear.mirrord.MirrordProjectService

class PythonCommandLineProvider : PythonCommandLineTargetEnvironmentProvider {
    override fun extendTargetEnvironment(
        project: Project,
        helpersAwareTargetRequest: HelpersAwareTargetEnvironmentRequest,
        pythonExecution: PythonExecution,
        runParams: PythonRunParams
    ) {
        val service = project.service<MirrordProjectService>()

        if (runParams is AbstractPythonRunConfiguration<*>) {
            val wsl = helpersAwareTargetRequest.targetEnvironmentRequest.let {
                if (it is WslTargetEnvironmentRequest) {
                    it.configuration.distribution
                } else {
                    null
                }
            }

            service.execManager.wrapper("pycharm", runParams.getEnvs()).apply {
                this.wsl = wsl
            }.start()?.let { executionInfo ->
                for (entry in executionInfo.environment.entries.iterator()) {
                    pythonExecution.addEnvironmentVariable(entry.key, entry.value)
                }

                for (key in executionInfo.envToUnset.orEmpty()) {
                    pythonExecution.envs.remove(key)
                }

                pythonExecution.addEnvironmentVariable("MIRRORD_DETECT_DEBUGGER_PORT", "pydevd")
            }
        }
    }
}

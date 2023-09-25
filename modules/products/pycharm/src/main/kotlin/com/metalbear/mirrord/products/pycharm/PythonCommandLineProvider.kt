@file:Suppress("UnstableApiUsage")

package com.metalbear.mirrord.products.pycharm

import com.intellij.execution.wsl.target.WslTargetEnvironmentRequest
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.jetbrains.python.run.AbstractPythonRunConfiguration
import com.jetbrains.python.run.PythonExecution
import com.jetbrains.python.run.PythonRunParams
import com.jetbrains.python.run.target.HelpersAwareTargetEnvironmentRequest
import com.jetbrains.python.run.target.PythonCommandLineTargetEnvironmentProvider
import com.metalbear.mirrord.CONFIG_ENV_NAME
import com.metalbear.mirrord.MirrordProjectService

// Note on how environment variables are cleared in this case:
// On testing I (Mehul) found that the environment variables are always overridden
// So if I start with state X, then add mirrordEnv(Y = X + Y), on a new run I will always start
// with X. (X here is completely unrelated to the environment variables set by the user)
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

            service.execManager.wrapper("pycharm").apply {
                this.wsl = wsl
                configFromEnv = runParams.getEnvs()[CONFIG_ENV_NAME]
            }.start()?.first?.let { env ->
                for (entry in env.entries.iterator()) {
                    pythonExecution.addEnvironmentVariable(entry.key, entry.value)
                }
                pythonExecution.addEnvironmentVariable("MIRRORD_DETECT_DEBUGGER_PORT", "pydevd")
            }
        }
    }
}

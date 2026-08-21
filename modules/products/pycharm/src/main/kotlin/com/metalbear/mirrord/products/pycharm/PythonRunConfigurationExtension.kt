package com.metalbear.mirrord.products.pycharm

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.openapi.components.service
import com.jetbrains.python.run.AbstractPythonRunConfiguration
import com.jetbrains.python.run.PythonRunConfigurationExtension
import com.metalbear.mirrord.MirrordProjectService
import com.metalbear.mirrord.bifrost.MirrordEnvironments

class PythonRunConfigurationExtension : PythonRunConfigurationExtension() {
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

        val environment = MirrordEnvironments.forRunProfile(configuration.project, configuration)

        val currentEnv = cmdLine.environment

        service.execManager.wrapper("pycharm", currentEnv, environment).start()?.let { executionInfo ->
            for (entry in executionInfo.environment.entries.iterator()) {
                currentEnv[entry.key] = entry.value
            }
            executionInfo.envToUnset?.let { envToUnset ->
                for (key in envToUnset.iterator()) {
                    currentEnv.remove(key)
                }
            }
        }

        currentEnv["MIRRORD_DETECT_DEBUGGER_PORT"] = "pydevd"
    }
}

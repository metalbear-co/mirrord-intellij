package com.metalbear.mirrord.products.rubymine

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.target.createEnvironmentRequest
import com.intellij.execution.wsl.target.WslTargetEnvironmentRequest
import com.intellij.openapi.components.service
import com.metalbear.mirrord.CONFIG_ENV_NAME
import com.metalbear.mirrord.MirrordProjectService
import org.jetbrains.plugins.ruby.ruby.run.configuration.AbstractRubyRunConfiguration
import org.jetbrains.plugins.ruby.ruby.run.configuration.RubyRunConfigurationExtension

class RubyMineRunConfigurationExtension : RubyRunConfigurationExtension() {
    override fun isApplicableFor(configuration: AbstractRubyRunConfiguration<*>): Boolean {
        return true
    }

    override fun isEnabledFor(
        applicableConfiguration: AbstractRubyRunConfiguration<*>,
        runnerSettings: RunnerSettings?
    ): Boolean {
        return true
    }

    override fun patchCommandLine(
        configuration: AbstractRubyRunConfiguration<*>,
        runnerSettings: RunnerSettings?,
        cmdLine: GeneralCommandLine,
        runnerId: String
    ) {
        val service = configuration.project.service<MirrordProjectService>()

        val wsl = when (val request = createEnvironmentRequest(configuration, configuration.project)) {
            is WslTargetEnvironmentRequest -> request.configuration.distribution!!
            else -> null
        }

        val currentEnv = configuration.envs

        service.execManager.start(
                wsl,
                "rubymine",
                currentEnv[CONFIG_ENV_NAME]
        )?.let {
                env ->
            for (entry in env.entries.iterator()) {
                currentEnv[entry.key] = entry.value
            }
        }
    }
}

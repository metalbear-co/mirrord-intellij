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
import kotlin.io.path.*

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
        println("############# patchCommandLine #################")
        println(cmdLine)
        println(runnerSettings)
        val service = configuration.project.service<MirrordProjectService>()

        val wsl = when (val request = createEnvironmentRequest(configuration, configuration.project)) {
            is WslTargetEnvironmentRequest -> request.configuration.distribution!!
            else -> null
        }
        val path = createTempFile("mirrord-ruby-launcher-", ".sh")
        path.writeText("#!/bin/sh\n")

        val currentEnv = configuration.envs

        service.execManager.wrapper("rubymine").apply {
            this.wsl = wsl
            configFromEnv = currentEnv[CONFIG_ENV_NAME]
        }.start()?.first?.let { env ->
            for (entry in env.entries.iterator()) {
                currentEnv[entry.key] = entry.value
            }
        }
        path.appendLines(currentEnv.entries.map { entry -> "export ${entry.key}=${entry.value}" })
        path.appendText(cmdLine.exePath)
        cmdLine.exePath = path.pathString
        path.toFile().setExecutable(true)
        println(path.readText())
    }
}

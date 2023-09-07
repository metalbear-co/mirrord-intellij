package com.metalbear.mirrord.products.rubymine

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.target.createEnvironmentRequest
import com.intellij.execution.wsl.target.WslTargetEnvironmentRequest
import com.intellij.openapi.components.service
import com.intellij.openapi.util.SystemInfo
import com.metalbear.mirrord.CONFIG_ENV_NAME
import com.metalbear.mirrord.MirrordError
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
        val service = configuration.project.service<MirrordProjectService>()
        val isMac = SystemInfo.isMac
        // TODO: would be nice to have a more robust RVM detection mechanism.
        val isRvm = cmdLine.exePath.contains("/.rvm/rubies/")
        if (isMac && !isRvm) {
            val e =  MirrordError("At the moment, only RVM Rubies are supported by mirrord on RubyMine on " +
                    "macOS, due to SIP.", " Support for other Rubies is tracked on " +
                    "https://github.com/metalbear-co/mirrord-intellij/issues/134.")
            e.showHelp(configuration.project)
            throw e
        }

        val wsl = when (val request = createEnvironmentRequest(configuration, configuration.project)) {
            is WslTargetEnvironmentRequest -> request.configuration.distribution!!
            else -> null
        }

        val currentEnv = configuration.envs


        service.execManager.wrapper("rubymine").apply {
            this.wsl = wsl
            if (isMac) {
                this.executable = cmdLine.exePath
            }
            configFromEnv = currentEnv[CONFIG_ENV_NAME]
        }.start()?.let { (env, patched) ->
            for (entry in env.entries.iterator()) {
                currentEnv[entry.key] = entry.value
                cmdLine.environment[entry.key] = entry.value
            }
            if (isMac && patched !== null) {
                cmdLine.exePath = patched
            }
        }

        if (isMac && isRvm) {
            val path = createTempFile("mirrord-ruby-launcher-", ".sh")
            // Using patched exe inside the launcher script.
            path.writeText("DYLD_INSERT_LIBRARIES=${currentEnv["DYLD_INSERT_LIBRARIES"]} ${cmdLine.exePath} $@")
            cmdLine.exePath = path.pathString
            path.toFile().setExecutable(true)
        }
    }
}

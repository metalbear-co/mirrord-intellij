@file:Suppress("UnstableApiUsage")

package com.metalbear.mirrord.products.idea

import com.intellij.execution.RunConfigurationExtension
import com.intellij.execution.configurations.JavaParameters
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.target.createEnvironmentRequest
import com.intellij.execution.wsl.target.WslTargetEnvironmentRequest
import com.intellij.openapi.components.service
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemRunConfiguration
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.metalbear.mirrord.CONFIG_ENV_NAME
import com.metalbear.mirrord.MirrordLogger
import com.metalbear.mirrord.MirrordProjectService
import java.util.concurrent.ConcurrentHashMap

class IdeaRunConfigurationExtension : RunConfigurationExtension() {
    /**
     * mirrord env set in ExternalRunConfigurations. Used for cleanup the configuration after the execution has ended.
     */
    private val runningProcessEnvs = ConcurrentHashMap<Project, Map<String, String>>()

    override fun isApplicableFor(configuration: RunConfigurationBase<*>): Boolean {
        val applicable = !configuration.name.startsWith("Build ") && !configuration.name.startsWith("Tomcat")

        if (!applicable) {
            MirrordLogger.logger.info("Configuration name %s ignored".format(configuration.name))
        }

        return applicable
    }

    override fun isEnabledFor(
        applicableConfiguration: RunConfigurationBase<*>,
        runnerSettings: RunnerSettings?
    ): Boolean {
        return true
    }

    private fun <T : RunConfigurationBase<*>> getMirrordConfigPath(configuration: T, params: JavaParameters): String? {
        return params.env[CONFIG_ENV_NAME] ?: if (configuration is ExternalSystemRunConfiguration) {
            val ext = configuration as ExternalSystemRunConfiguration
            ext.settings.env[CONFIG_ENV_NAME]
        } else {
            null
        }
    }

    override fun <T : RunConfigurationBase<*>> updateJavaParameters(
        configuration: T,
        params: JavaParameters,
        runnerSettings: RunnerSettings?
    ) {
        val service = configuration.project.service<MirrordProjectService>()

        MirrordLogger.logger.debug("wsl check")
        val wsl = when (val request = createEnvironmentRequest(configuration, configuration.project)) {
            is WslTargetEnvironmentRequest -> request.configuration.distribution!!
            else -> null
        }

        val extraEnv = if (configuration is ExternalSystemRunConfiguration) {
            val extraEnv = params.env + configuration.settings.env
            extraEnv
        } else {
            params.env
        }

        service.execManager.wrapper("idea", extraEnv).apply {
            this.wsl = wsl
        }.start()?.let { executionInfo ->
            val mirrordEnv = executionInfo.environment + mapOf(Pair("MIRRORD_DETECT_DEBUGGER_PORT", "javaagent"))
            params.env = params.env + mirrordEnv - executionInfo.envToUnset.orEmpty().toSet()
            runningProcessEnvs[configuration.project] = params.env.toMap()

            // Gradle support (and external system configuration)
            if (configuration is ExternalSystemRunConfiguration) {
                runningProcessEnvs[configuration.project] = configuration.settings.env.toMap()
                val env = configuration.settings.env +
                        mirrordEnv -
                        executionInfo.envToUnset.orEmpty().toSet()
                configuration.settings.env = env
            }
            MirrordLogger.logger.debug("setting env and finishing")
        }
    }

    /**
     * Remove mirrord env leftovers from the external system configurations.
     */
    override fun attachToProcess(
        configuration: RunConfigurationBase<*>,
        handler: ProcessHandler,
        runnerSettings: RunnerSettings?
    ) {
        if (configuration is ExternalSystemRunConfiguration) {
            val envsToRestore = runningProcessEnvs.remove(configuration.project) ?: return

            handler.addProcessListener(object : ProcessListener {
                override fun processTerminated(event: ProcessEvent) {
                    configuration.settings.env.apply {
                        clear()
                        putAll(envsToRestore)
                    }
                }

                override fun startNotified(event: ProcessEvent) {}

                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {}
            })
        }
    }
}

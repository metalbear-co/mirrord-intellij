@file:Suppress("UnstableApiUsage")

package com.metalbear.mirrord.products.idea

import com.intellij.execution.CommonProgramRunConfigurationParameters
import com.intellij.execution.RunConfigurationExtension
import com.intellij.execution.configurations.JavaParameters
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemRunConfiguration
import com.intellij.openapi.util.Key
import com.metalbear.mirrord.MirrordLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * For overriding the `isApplicableFor` check on run configurations.
 * Set MIRRORD_FORCE_RUN=true to ensure we consider the run configuration able to run with mirrord.
 * NOTE: mirrord must _still be enabled_ to run on the run configuration.
 */
const val FORCE_RUN_ENV_NAME: String = "MIRRORD_FORCE_RUN"

private const val GRADLE_RUN_CONFIGURATION = "org.jetbrains.plugins.gradle.service.execution.GradleRunConfiguration"

private val GRADLE_RUN_TASKS = setOf("run", "bootRun", "runIde", "serve", "start", "quarkusDev")

internal fun isIdeaConfigurationApplicableForMirrord(configuration: RunConfigurationBase<*>): Boolean {
    val skipTomcat = configuration.name.startsWith("Build ") || configuration.name.startsWith("Tomcat")

    val gradleTaskNames = (configuration as? ExternalSystemRunConfiguration)?.settings?.taskNames ?: emptyList()
    val skipGradleBuild = configuration.javaClass.name == GRADLE_RUN_CONFIGURATION &&
            gradleTaskNames.none { task -> GRADLE_RUN_TASKS.any { task.contains(it, ignoreCase = true) } }

    val forceRunMirrord = getForceRunMirrord(configuration)

    return forceRunMirrord || !(skipTomcat || skipGradleBuild)
}

internal fun getIdeaConfigurationEnv(configuration: RunConfigurationBase<*>): Map<String, String> {
    return when (configuration) {
        is ExternalSystemRunConfiguration -> configuration.settings.env
        is CommonProgramRunConfigurationParameters -> configuration.envs
        else -> emptyMap()
    }
}

private fun getForceRunMirrord(configuration: RunConfigurationBase<*>): Boolean {
    return if (configuration is ExternalSystemRunConfiguration) {
        configuration.settings.env[FORCE_RUN_ENV_NAME].toBoolean()
    } else {
        false
    }
}

class IdeaRunConfigurationExtension : RunConfigurationExtension() {
    /**
     * mirrord env set in ExternalRunConfigurations. Used for cleanup the configuration after the execution has ended.
     */
    private val runningProcessEnvs = ConcurrentHashMap<RunConfigurationBase<*>, Map<String, String>>()

    override fun isApplicableFor(configuration: RunConfigurationBase<*>): Boolean {
        val applicable = isIdeaConfigurationApplicableForMirrord(configuration)

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

    override fun <T : RunConfigurationBase<*>> updateJavaParameters(
        configuration: T,
        params: JavaParameters,
        runnerSettings: RunnerSettings?
    ) {
        val executionInfo = IdeaMirrordPreparationStore.consume(configuration) ?: run {
            // Main mirrord initialization is prepared by a before-run task to avoid blocking here under read lock.
            MirrordLogger.logger.debug("No prepared mirrord execution info for `${configuration.name}`, skipping")
            return
        }

        val mirrordEnv = executionInfo.environment + mapOf("MIRRORD_DETECT_DEBUGGER_PORT" to "javaagent")
        params.env = params.env + mirrordEnv - executionInfo.envToUnset.orEmpty().toSet()

        // Gradle support (and external system configuration)
        if (configuration is ExternalSystemRunConfiguration) {
            runningProcessEnvs[configuration] = configuration.settings.env.toMap()
            val env = configuration.settings.env +
                    mirrordEnv -
                    executionInfo.envToUnset.orEmpty().toSet()
            configuration.settings.env = env
        }
        MirrordLogger.logger.debug("setting env and finishing")
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
            val envsToRestore = runningProcessEnvs.remove(configuration) ?: return

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
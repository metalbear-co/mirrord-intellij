@file:Suppress("UnstableApiUsage")

package com.metalbear.mirrord.products.quarkus

import com.intellij.execution.RunConfigurationExtension
import com.intellij.execution.configurations.JavaParameters
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.process.ProcessHandler
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap
//import com.intellij.quarkus.run.QsTargetConfigurationExtension
import com.intellij.quarkus.run.QsTargetConfigurationExtension

class QuarkusRunConfigurationExtension : RunConfigurationExtension() {
    /**
     * mirrord env set in ExternalRunConfigurations. Used for cleanup the configuration after the execution has ended.
     */
    private val runningProcessEnvs = ConcurrentHashMap<Project, Map<String, String>>()

    override fun isApplicableFor(configuration: RunConfigurationBase<*>): Boolean {
        return true
    }

    override fun isEnabledFor(
        applicableConfiguration: RunConfigurationBase<*>,
        runnerSettings: RunnerSettings?
    ): Boolean {
        return true
    }

    private fun <T : RunConfigurationBase<*>> getMirrordConfigPath(configuration: T, params: JavaParameters): String? {
        return null
    }

    override fun <T : RunConfigurationBase<*>> updateJavaParameters(
        configuration: T,
        params: JavaParameters,
        runnerSettings: RunnerSettings?
    ) { }

    /**
     * Remove mirrord env leftovers from the external system configurations.
     */
    override fun attachToProcess(
        configuration: RunConfigurationBase<*>,
        handler: ProcessHandler,
        runnerSettings: RunnerSettings?
    ) { }
}

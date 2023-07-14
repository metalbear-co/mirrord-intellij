package com.metalbear.mirrord.products.nodejs

import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.target.createEnvironmentRequest
import com.intellij.execution.wsl.target.WslTargetEnvironmentRequest
import com.intellij.javascript.nodejs.execution.AbstractNodeTargetRunProfile
import com.intellij.javascript.nodejs.execution.runConfiguration.AbstractNodeRunConfigurationExtension
import com.intellij.javascript.nodejs.execution.runConfiguration.NodeRunConfigurationLaunchSession
import com.intellij.openapi.components.service
import com.intellij.openapi.options.SettingsEditor
import com.jetbrains.nodejs.run.NodeJsRunConfiguration
import com.metalbear.mirrord.CONFIG_ENV_NAME
import com.metalbear.mirrord.MirrordProjectService

class NodeRunConfigurationExtension : AbstractNodeRunConfigurationExtension() {

    override fun <P : AbstractNodeTargetRunProfile> createEditor(configuration: P): SettingsEditor<P> {
        return RunConfigurationSettingsEditor(configuration)
    }

    override fun createLaunchSession(
        configuration: AbstractNodeTargetRunProfile,
        environment: ExecutionEnvironment
    ): NodeRunConfigurationLaunchSession? {
        val service = configuration.project.service<MirrordProjectService>()

        // Find out if we're running in wsl.
        val wsl = when (val request = createEnvironmentRequest(configuration, service.project)) {
            is WslTargetEnvironmentRequest -> request.configuration.distribution!!
            else -> null
        }

        val config = configuration as NodeJsRunConfiguration
        service.execManager.start(wsl, "npm", config.envs[CONFIG_ENV_NAME])?.let { env ->
            config.envs = config.envs + env
        }

        return null
    }

    override fun getEditorTitle(): String? {
        return null
    }

    override fun isApplicableFor(profile: AbstractNodeTargetRunProfile): Boolean {
        return true
    }
}

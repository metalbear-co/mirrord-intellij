package com.metalbear.mirrord.products.nodejs

import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.wsl.target.WslTargetEnvironmentRequest
import com.intellij.javascript.nodejs.execution.AbstractNodeTargetRunProfile
import com.intellij.javascript.nodejs.execution.NodeTargetRun
import com.intellij.javascript.nodejs.execution.runConfiguration.AbstractNodeRunConfigurationExtension
import com.intellij.javascript.nodejs.execution.runConfiguration.NodeRunConfigurationLaunchSession
import com.intellij.openapi.components.service
import com.intellij.openapi.options.SettingsEditor
import com.jetbrains.nodejs.run.NodeJsRunConfiguration
import com.metalbear.mirrord.CONFIG_ENV_NAME
import com.metalbear.mirrord.MirrordProjectService
import javax.swing.JPanel

class NodeRunConfigurationExtension : AbstractNodeRunConfigurationExtension() {

    override fun <P : AbstractNodeTargetRunProfile> createEditor(configuration: P): SettingsEditor<P> {
        return object : SettingsEditor<P>() {
            override fun resetEditorFrom(s: P) {}

            override fun applyEditorTo(s: P) {}

            override fun createEditor() = JPanel()
        }
    }

    override fun getEditorTitle(): String? {
        return null
    }

    override fun createLaunchSession(configuration: AbstractNodeTargetRunProfile, environment: ExecutionEnvironment): NodeRunConfigurationLaunchSession {
        return object : NodeRunConfigurationLaunchSession() {
            override fun addNodeOptionsTo(targetRun: NodeTargetRun) {
                val service = targetRun.project.service<MirrordProjectService>()
                val wsl = when (val request = targetRun.request) {
                    is WslTargetEnvironmentRequest -> request.configuration.distribution
                    else -> null
                }

                // following try-catch is to maintain backward compatibility with older versions of webstorm
                val extraEnvVars = try {
                            targetRun.envData.envs
                        } catch (e: NoSuchMethodError) {
                            val config = configuration as NodeJsRunConfiguration
                            config.envs
                        }

                service.execManager.wrapper("nodejs", extraEnvVars).apply {
                    this.wsl = wsl

                }.start()?.first?.forEach { (key, value) ->
                    targetRun.commandLineBuilder.addEnvironmentVariable(key, value)
                }
            }
        }
    }

    override fun isApplicableFor(profile: AbstractNodeTargetRunProfile): Boolean {
        return profile is NodeJsRunConfiguration
    }
}

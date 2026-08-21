package com.metalbear.mirrord.products.nodejs

import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.javascript.nodejs.execution.AbstractNodeTargetRunProfile
import com.intellij.javascript.nodejs.execution.NodeTargetRun
import com.intellij.javascript.nodejs.execution.runConfiguration.AbstractNodeRunConfigurationExtension
import com.intellij.javascript.nodejs.execution.runConfiguration.NodeRunConfigurationLaunchSession
import com.intellij.openapi.components.service
import com.intellij.openapi.options.SettingsEditor
import com.jetbrains.nodejs.run.NodeJsRunConfiguration
import com.metalbear.mirrord.MirrordProjectService
import com.metalbear.mirrord.bifrost.MirrordEnvironments
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

                // This single expression is where COR-1385 began. `targetRun.request` already
                // describes where the IDE is about to launch Node — a dev container, a WSL
                // distribution, or nothing at all — but the old code recognised exactly one of
                // those and treated every other answer as "local". A dev container fell into
                // `else -> null`, so mirrord ran on the host while Node ran in the container.
                //
                // Nothing below this line needed to change: `commandLineBuilder` is already
                // target-aware, so the environment variables mirrord returns are applied
                // wherever the IDE launches. Only the producer was ever on the wrong machine.
                val environment = MirrordEnvironments.forRequest(targetRun.project, targetRun.request)

                // following try-catch is to maintain backward compatibility with older versions of webstorm
                val extraEnvVars = try {
                    targetRun.envData.envs
                } catch (e: NoSuchMethodError) {
                    val config = configuration as NodeJsRunConfiguration
                    config.envs
                }

                service.execManager.wrapper("nodejs", extraEnvVars, environment).start()?.let { executionInfo ->
                    executionInfo.environment.forEach { (key, value) ->
                        targetRun.commandLineBuilder.addEnvironmentVariable(key, value)
                    }
                    executionInfo.envToUnset?.let { keys ->
                        for (key in keys.iterator()) {
                            targetRun.commandLineBuilder.removeEnvironmentVariable(key)
                        }
                    }
                }
            }
        }
    }

    override fun isApplicableFor(profile: AbstractNodeTargetRunProfile): Boolean {
        return profile is NodeJsRunConfiguration
    }
}

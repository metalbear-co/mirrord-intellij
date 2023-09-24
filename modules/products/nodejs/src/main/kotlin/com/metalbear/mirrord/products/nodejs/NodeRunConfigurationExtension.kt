@file:Suppress("UnstableApiUsage")

package com.metalbear.mirrord.products.nodejs

import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.wsl.target.WslTargetEnvironmentRequest
import com.intellij.javascript.nodejs.execution.AbstractNodeTargetRunProfile
import com.intellij.javascript.nodejs.execution.NodeTargetRun
import com.intellij.javascript.nodejs.execution.runConfiguration.AbstractNodeRunConfigurationExtension
import com.intellij.javascript.nodejs.execution.runConfiguration.NodeRunConfigurationLaunchSession
import com.intellij.openapi.components.service
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.jetbrains.nodejs.run.NodeJsRunConfiguration
import com.metalbear.mirrord.CONFIG_ENV_NAME
import com.metalbear.mirrord.MirrordProjectService
import java.util.concurrent.ConcurrentHashMap
import javax.swing.JPanel

class NodeRunConfigurationExtension : AbstractNodeRunConfigurationExtension() {

    private val runningProcessEnvs = ConcurrentHashMap<Project, Map<String, String>>()

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
                service.execManager.wrapper("nodejs").apply {
                    this.wsl = wsl
                    // following try-catch is to maintain backward compatibility with older versions of webstorm
                    configFromEnv = try {
                        targetRun.envData.envs[CONFIG_ENV_NAME]
                    } catch (e: NoSuchMethodError) {
                        val config = configuration as NodeJsRunConfiguration
                        config.envs[CONFIG_ENV_NAME]
                    }
                }.start()?.let { (mirrordEnv, _) ->

                    runningProcessEnvs[configuration.project] = try {
                        targetRun.envData.envs
                    } catch (e: NoSuchMethodError) {
                        val config = configuration as NodeJsRunConfiguration
                        config.envs
                    }

                    mirrordEnv.forEach { (key, value) ->
                        targetRun.commandLineBuilder.addEnvironmentVariable(key, value)
                    }
                }
            }
        }
    }

    override fun isApplicableFor(profile: AbstractNodeTargetRunProfile): Boolean {
        return profile is NodeJsRunConfiguration
    }

    override fun attachToProcess(
        configuration: AbstractNodeTargetRunProfile,
        handler: ProcessHandler,
        runnerSettings: RunnerSettings?
    ) {
        val envsToRestore = runningProcessEnvs.remove(configuration.project) ?: return

        handler.addProcessListener(object : ProcessListener {
            override fun processTerminated(event: ProcessEvent) {
                configuration.envData.envs.apply {
                    clear()
                    putAll(envsToRestore)
                }
            }

            override fun startNotified(event: ProcessEvent) {}

            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {}
        })
    }
}

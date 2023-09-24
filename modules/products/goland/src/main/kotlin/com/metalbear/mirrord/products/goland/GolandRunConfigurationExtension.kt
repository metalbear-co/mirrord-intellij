@file:Suppress("UnstableApiUsage")

package com.metalbear.mirrord.products.goland

import com.goide.execution.GoRunConfigurationBase
import com.goide.execution.GoRunningState
import com.goide.execution.extension.GoRunConfigurationExtension
import com.goide.util.GoCommandLineParameter.PathParameter
import com.goide.util.GoExecutor
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.target.TargetedCommandLineBuilder
import com.intellij.execution.wsl.target.WslTargetEnvironmentRequest
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SystemInfo
import com.metalbear.mirrord.CONFIG_ENV_NAME
import com.metalbear.mirrord.MirrordPathManager
import com.metalbear.mirrord.MirrordProjectService
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

class GolandRunConfigurationExtension : GoRunConfigurationExtension() {

    private val runningProcessEnvs = ConcurrentHashMap<Project, Map<String, String>>()

    override fun isApplicableFor(configuration: GoRunConfigurationBase<*>): Boolean {
        return true
    }

    override fun isEnabledFor(
        applicableConfiguration: GoRunConfigurationBase<*>,
        runnerSettings: RunnerSettings?
    ): Boolean {
        return true
    }

    override fun patchCommandLine(
        configuration: GoRunConfigurationBase<*>,
        runnerSettings: RunnerSettings?,
        cmdLine: TargetedCommandLineBuilder,
        runnerId: String,
        state: GoRunningState<out GoRunConfigurationBase<*>>,
        commandLineType: GoRunningState.CommandLineType
    ) {
        if (commandLineType == GoRunningState.CommandLineType.RUN) {
            val service = configuration.getProject().service<MirrordProjectService>()

            val wsl = state.targetEnvironmentRequest?.let {
                if (it is WslTargetEnvironmentRequest) {
                    it.configuration.distribution
                } else {
                    null
                }
            }

            service.execManager.wrapper("goland").apply {
                this.wsl = wsl
                configFromEnv = configuration.getCustomEnvironment()[CONFIG_ENV_NAME]
            }.start()?.first?.let { env ->


                val project = configuration.getProject()
                // the environment field is inaccessible in the commandline
                // we need to use reflection to access it
                try {
                    runningProcessEnvs[project] =
                        cmdLine::class.java.getDeclaredField("environment").apply {
                            isAccessible = true
                        }.get(cmdLine) as Map<String, String>
                } catch (e: Exception) {
                    project
                        .service<MirrordProjectService>()
                        .notifier
                        .notification(
                            "An exception occurred while saving the current environment variables' state" +
                                    "mirrord's custom environment variables might not be cleared.",
                            NotificationType.WARNING
                        )
                }

                env.entries.forEach { entry ->
                    cmdLine.addEnvironmentVariable(entry.key, entry.value)
                }
                cmdLine.addEnvironmentVariable("MIRRORD_SKIP_PROCESSES", "dlv;debugserver;go")
            }
        }
        super.patchCommandLine(configuration, runnerSettings, cmdLine, runnerId, state, commandLineType)
    }

    override fun patchExecutor(
        configuration: GoRunConfigurationBase<*>,
        runnerSettings: RunnerSettings?,
        executor: GoExecutor,
        runnerId: String,
        state: GoRunningState<out GoRunConfigurationBase<*>>,
        commandLineType: GoRunningState.CommandLineType
    ) {
        val service = configuration.getProject().service<MirrordProjectService>()

        if (commandLineType == GoRunningState.CommandLineType.RUN &&
            service.enabled &&
            SystemInfo.isMac &&
            state.isDebug
        ) {
            val delvePath = getCustomDelvePath()
            // convert the delve file to an executable
            val delveExecutable = Paths.get(delvePath).toFile()
            if (delveExecutable.exists()) {
                if (!delveExecutable.canExecute()) {
                    delveExecutable.setExecutable(true)
                }
                // parameters returns a copy, and we can't modify it so need to reset it.
                val patchedParameters = executor.parameters
                for (i in 0 until patchedParameters.size) {
                    // no way to reset the whole commandline, so we just remove each entry.
                    executor.withoutParameter(0)
                    val value = patchedParameters[i]
                    if (value.toPresentableString().endsWith("/dlv", true)) {
                        patchedParameters[i] = PathParameter(delveExecutable.toString())
                    }
                }
                executor.withParameters(*patchedParameters.toTypedArray())
            }
        }
        super.patchExecutor(configuration, runnerSettings, executor, runnerId, state, commandLineType)
    }

    override fun attachToProcess(
        configuration: GoRunConfigurationBase<*>,
        handler: ProcessHandler,
        runnerSettings: RunnerSettings?
    ) {
        val envsToRestore = runningProcessEnvs.remove(configuration.getProject()) ?: return

        handler.addProcessListener(object : ProcessListener {
            override fun processTerminated(event: ProcessEvent) {
                configuration.getCustomEnvironment().apply {
                    clear()
                    putAll(envsToRestore)
                }
            }

            override fun startNotified(event: ProcessEvent) {}

            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {}
        })
    }
}

private fun getCustomDelvePath(): String {
    return MirrordPathManager.getBinary("dlv", false)!!
}

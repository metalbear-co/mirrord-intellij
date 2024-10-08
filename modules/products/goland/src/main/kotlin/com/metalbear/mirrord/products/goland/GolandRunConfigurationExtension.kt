package com.metalbear.mirrord.products.goland

import com.github.zafarkhaja.semver.Version
import com.goide.execution.GoRunConfigurationBase
import com.goide.execution.GoRunningState
import com.goide.execution.extension.GoRunConfigurationExtension
import com.goide.util.GoCommandLineParameter.PathParameter
import com.goide.util.GoExecutor
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.target.TargetedCommandLineBuilder
import com.intellij.execution.wsl.target.WslTargetEnvironmentRequest
import com.intellij.openapi.components.service
import com.intellij.openapi.util.SystemInfo
import com.metalbear.mirrord.MirrordLogger
import com.metalbear.mirrord.MirrordPathManager
import com.metalbear.mirrord.MirrordProjectService
import java.nio.file.Paths

class GolandRunConfigurationExtension : GoRunConfigurationExtension() {

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

            service.execManager.wrapper("goland", configuration.getCustomEnvironment()).apply {
                this.wsl = wsl
            }.start()?.let { executionInfo ->

                for (entry in executionInfo.environment.entries.iterator()) {
                    cmdLine.addEnvironmentVariable(entry.key, entry.value)
                }
                cmdLine.addEnvironmentVariable("MIRRORD_SKIP_PROCESSES", "dlv;debugserver;go")

                executionInfo.envToUnset?.let { keys ->
                    for (key in keys.iterator()) {
                        cmdLine.removeEnvironmentVariable(key)
                    }
                }
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

                val ourDelveVersion = try {
                    getDelveVersion(delvePath)
                } catch (e: Throwable) {
                    MirrordLogger.logger.error("Failed to get version of our bundled DLV $delvePath", e)
                    null
                }

                // parameters returns a copy, and we can't modify it so need to reset it.
                val patchedParameters = executor.parameters
                for (i in 0 until patchedParameters.size) {
                    // no way to reset the whole commandline, so we just remove each entry.
                    executor.withoutParameter(0)

                    val foundDelvePath = patchedParameters[i].toPresentableString()

                    if (!foundDelvePath.endsWith("/dlv", true)) {
                        continue
                    }

                    val delveVersion = if (ourDelveVersion == null) {
                        null
                    } else {
                        try {
                            getDelveVersion(foundDelvePath)
                        } catch (e: Throwable) {
                            MirrordLogger
                                .logger
                                .error("Failed to get version of DLV found in executor parameters $foundDelvePath", e)
                            null
                        }
                    }

                    if (delveVersion == null || delveVersion.lessThan(ourDelveVersion)) {
                        patchedParameters[i] = PathParameter(delveExecutable.toString())
                    }
                }
                executor.withParameters(*patchedParameters.toTypedArray())
            }
        }
        super.patchExecutor(configuration, runnerSettings, executor, runnerId, state, commandLineType)
    }

    private fun getCustomDelvePath(): String {
        return MirrordPathManager.getBinary("dlv", false)!!
    }

    private fun getDelveVersion(path: String): Version {
        val process = ProcessBuilder(path, "version")
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()

        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw Exception("dlv version command exited with code $exitCode")
        }

        val versionLine = process
            .inputStream
            .bufferedReader()
            .readLines()
            .find { it.startsWith("Version: ") } ?: throw Exception("no output line starts with 'Version: '")

        return Version.valueOf(versionLine.removePrefix("Version: "))
    }
}

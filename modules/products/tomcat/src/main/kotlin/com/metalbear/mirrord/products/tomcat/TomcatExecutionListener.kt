@file:Suppress("UnstableApiUsage")

package com.metalbear.mirrord.products.tomcat

import com.intellij.execution.ExecutionListener
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.target.createEnvironmentRequest
import com.intellij.execution.util.EnvironmentVariable
import com.intellij.execution.wsl.target.WslTargetEnvironmentRequest
import com.intellij.javaee.appServers.integration.impl.ApplicationServerImpl
import com.intellij.javaee.appServers.run.configuration.CommonStrategy
import com.intellij.javaee.appServers.run.configuration.RunnerSpecificLocalConfigurationBit
import com.intellij.javaee.appServers.run.localRun.ScriptInfo
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.service
import com.intellij.openapi.util.SystemInfo
import com.metalbear.mirrord.CONFIG_ENV_NAME
import com.metalbear.mirrord.MirrordLogger
import com.metalbear.mirrord.MirrordProjectService
import org.jetbrains.idea.tomcat.server.TomcatPersistentData
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

private const val DEFAULT_TOMCAT_SERVER_PORT: String = "8005"

private fun getTomcatServerPort(): String {
    return System.getenv("MIRRORD_TOMCAT_SERVER_PORT") ?: DEFAULT_TOMCAT_SERVER_PORT
}

data class SavedStartupScriptInfo(val useDefault: Boolean, val script: String?, val args: String?, val vmArgs: String?)

data class SavedConfigData(val envVars: List<EnvironmentVariable>, val scriptInfo: SavedStartupScriptInfo?)

data class CommandLineWithArgs(val command: String, val args: String?)

class TomcatExecutionListener : ExecutionListener {
    private val savedEnvs: ConcurrentHashMap<String, SavedConfigData> = ConcurrentHashMap()

    private fun getConfig(env: ExecutionEnvironment): RunnerSpecificLocalConfigurationBit? {
        if (!env.toString().startsWith("Tomcat")) {
            return null
        }

        val settings = env.configurationSettings ?: return null

        return if (settings is RunnerSpecificLocalConfigurationBit) {
            settings
        } else {
            null
        }
    }

    /**
     * Returns a String with the path of the script that will be executed, based on the [scriptInfo].
     * If the info is not available, which by looking at [ScriptInfo] seems possible (though we don't know when), the
     * default script will be guessed based on the location of the tomcat installation, taken from [env].
     */
    private fun getStartScript(scriptInfo: ScriptInfo, env: ExecutionEnvironment): CommandLineWithArgs {
        val commandLine: String = if (scriptInfo.USE_DEFAULT) {
            scriptInfo.defaultScript.ifBlank {
                // We return the default script if it's not blank. If it's blank we're guessing the path on our own,
                // based on the tomcat installation location.
                val tomcatLocation =
                    (((env.runProfile as CommonStrategy).applicationServer as ApplicationServerImpl).persistentData as TomcatPersistentData).HOME
                val defaultScript = Paths.get(tomcatLocation, "bin/catalina.sh")
                defaultScript.toString()
            }
        } else {
            scriptInfo.SCRIPT
        }
        // Split on the first space that is not preceded by a backslash.
        // 4 backslashes in the string are 1 in the regex.
        val split = commandLine.split("(?<!\\\\) ".toRegex(), limit = 2)
        val command = split.first()
        val args = split.getOrNull(1)
        return CommandLineWithArgs(command, args)
    }

    override fun processStartScheduled(executorId: String, env: ExecutionEnvironment) {
        getConfig(env)?.let { config ->
            val envVars = config.envVariables

            val service = env.project.service<MirrordProjectService>()

            MirrordLogger.logger.debug("wsl check")
            val wsl = when (val request = createEnvironmentRequest(env.runProfile, env.project)) {
                is WslTargetEnvironmentRequest -> request.configuration.distribution!!
                else -> null
            }

            val startupInfo = config.startupInfo
            val (script, args) = getStartScript(startupInfo, env)

            try {
                service.execManager.wrapper("idea").apply {
                    this.wsl = wsl
                    this.executable = script
                    configFromEnv = envVars.find { e -> e.name == CONFIG_ENV_NAME }?.VALUE
                }.start()?.let { (env, patchedPath) ->
                    // `MIRRORD_IGNORE_DEBUGGER_PORTS` should allow clean shutdown of the app
                    // even if `outgoing` feature is enabled.
                    val mirrordEnv = env + mapOf(Pair("MIRRORD_DETECT_DEBUGGER_PORT", "javaagent"), Pair("MIRRORD_IGNORE_DEBUGGER_PORTS", getTomcatServerPort()))

                    // If we're on macOS we're going to SIP-patch the script and change info, so save script info.
                    val savedScriptInfo = if (SystemInfo.isMac) {
                        SavedStartupScriptInfo(startupInfo.USE_DEFAULT, startupInfo.SCRIPT, startupInfo.PROGRAM_PARAMETERS, startupInfo.VM_PARAMETERS)
                    } else {
                        null
                    }

                    savedEnvs[executorId] = SavedConfigData(envVars.toList(), savedScriptInfo)
                    envVars.addAll(mirrordEnv.map { (k, v) -> EnvironmentVariable(k, v, false) })
                    config.setEnvironmentVariables(envVars)

                    if (SystemInfo.isMac) {
                        patchedPath?.let {
                            config.startupInfo.USE_DEFAULT = false
                            config.startupInfo.SCRIPT = it
                            config.startupInfo.VM_PARAMETERS = config.appendVMArguments(config.createJavaParameters())
//                            config.startupInfo.VM_PARAMETERS = config.getEnvVarValues()
                            args?.let {
                                config.startupInfo.PROGRAM_PARAMETERS = args
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                MirrordLogger.logger.debug("Running tomcat project failed: ", e)
                // Error notifications were already fired.
                // We can't abort the execution here, so we let the app run without mirrord.
                service.notifier.notifySimple(
                    "Cannot abort run due to platform limitations, running without mirrord",
                    NotificationType.WARNING
                )
            }
        }

        super.processStartScheduled(executorId, env)
    }

    private fun restoreConfig(executorId: String, config: RunnerSpecificLocalConfigurationBit) {
        val saved = savedEnvs.remove(executorId) ?: return
        config.setEnvironmentVariables(saved.envVars)
        if (SystemInfo.isMac) {
            saved.scriptInfo?.let {
                config.startupInfo.USE_DEFAULT = it.useDefault
                it.script?.let { scriptPath ->
                    config.startupInfo.SCRIPT = scriptPath
                }
                it.args?.let { args ->
                    config.startupInfo.PROGRAM_PARAMETERS = args
                }
                it.vmArgs?.let { vmArgs ->
                    config.startupInfo.VM_PARAMETERS = vmArgs
                }
            }
        }
    }

    override fun processNotStarted(executorId: String, env: ExecutionEnvironment) {
        getConfig(env)?.let {
            restoreConfig(executorId, it)
        }
        super.processNotStarted(executorId, env)
    }

    override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
        getConfig(env)?.let {
            restoreConfig(executorId, it)
        }
        super.processStarted(executorId, env, handler)
    }
}

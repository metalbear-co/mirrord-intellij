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
import com.intellij.javaee.appServers.run.localRun.CommandLineExecutableObject
import com.intellij.javaee.appServers.run.localRun.ExecutableObject
import com.intellij.javaee.appServers.run.localRun.ScriptHelper
import com.intellij.javaee.appServers.run.localRun.ScriptInfo
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.service
import com.intellij.openapi.util.SystemInfo
import com.metalbear.mirrord.MirrordLogger
import com.metalbear.mirrord.MirrordProjectService
import org.jetbrains.idea.tomcat.server.TomcatPersistentData
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

private const val DEFAULT_TOMCAT_SERVER_PORT: String = "8005"

private fun getTomcatServerPort(): String {
    return System.getenv("MIRRORD_TOMCAT_SERVER_PORT") ?: DEFAULT_TOMCAT_SERVER_PORT
}

/**
 * Extending the ScriptInfo class to replace the script info of the run with an object of this class so that we can
 * override `getScript` in order to run a patched script instead of the original one on macOS if the original is SIP
 * protected.
 * @property params first element is script, then args, e.g.:
 * ["/var/folders/4l/810mmn597cx7zy5w2bk11clh0000gn/T/mirrord-bin/opt/homebrew/Cellar/tomcat@8/8.5.95/libexec/bin/catalina.sh", "run"]
 */
class PatchedScriptInfo(
    scriptsHelper: ScriptHelper?,
    parent: CommonStrategy,
    startupNotShutdown: Boolean,
    private val params: Array<String>
) :
    ScriptInfo(scriptsHelper, parent, startupNotShutdown) {

    /** Get the original script object, forcefully change the accessibility of the private parameters field, then write
     * the patched parameters to that field, and return the changed script object.
     */
    override fun getScript(): ExecutableObject? {
        MirrordLogger.logger.debug("getScript override")
        val startupScript = super.getScript()
        MirrordLogger.logger.debug("setting params to: ${params.joinToString(" ")}")
        val cmdExecutableObject = startupScript as? CommandLineExecutableObject
        val cmd = cmdExecutableObject ?: return startupScript
        val myParamsField = CommandLineExecutableObject::class.java.getDeclaredField("myParameters")
        myParamsField.isAccessible = true
        myParamsField.set(cmd, params)
        MirrordLogger.logger.debug("getScript survived forced access")
        return startupScript
    }
}

/**
 * Create script object that will return a script object of SIP-patched script instead of the original.
 */
fun patchedScriptFromScript(scriptInfo: ScriptInfo, script: String, args: String?): PatchedScriptInfo {
    MirrordLogger.logger.debug("patchedScriptFromScript - script: $script, args: $args")
    val argList = args?.split("(?<!\\\\) ".toRegex()) ?: emptyList()
    val params = listOf(script) + argList
    val parentField = ScriptInfo::class.java.getDeclaredField("myParent")
    val startupNotShutdownField = ScriptInfo::class.java.getDeclaredField("myStartupNotShutdown")
    parentField.isAccessible = true
    startupNotShutdownField.isAccessible = true
    val parent = parentField.get(scriptInfo) as CommonStrategy
    val startupNotShutdown = startupNotShutdownField.get(scriptInfo) as Boolean
    MirrordLogger.logger.debug("patchedScriptFromScript survived forced accesses")
    return PatchedScriptInfo(scriptInfo.scriptHelper, parent, startupNotShutdown, params.toTypedArray())
}

data class SavedConfigData(val envVars: List<EnvironmentVariable>, var scriptInfo: ScriptInfo?)

data class CommandLineWithArgs(val command: String, val args: String?)

class TomcatExecutionListener : ExecutionListener {
    private val savedEnvs: ConcurrentHashMap<String, SavedConfigData> = ConcurrentHashMap()

    private fun getConfig(env: ExecutionEnvironment): RunnerSpecificLocalConfigurationBit? {
        MirrordLogger.logger.debug("getConfig - env: $env")

        val settings = env.configurationSettings ?: return null
        MirrordLogger.logger.debug("getConfig - settings: $settings")

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
    private fun getStartScript(scriptInfo: ScriptInfo, env: ExecutionEnvironment): CommandLineWithArgs? {
        MirrordLogger.logger.debug("getStartScript tomcat")
        return if (scriptInfo.USE_DEFAULT) {
            MirrordLogger.logger.debug("using default tomcat script")
            val commandLine = scriptInfo.defaultScript.ifBlank {
                MirrordLogger.logger.debug("default script was blank")
                // We return the default script if it's not blank. If it's blank we're guessing the path on our own,
                // based on the tomcat installation location.
                val tomcatLocation =
                    (((env.runProfile as? CommonStrategy)?.applicationServer as? ApplicationServerImpl)?.persistentData as? TomcatPersistentData)?.HOME
                val tomcatHome = tomcatLocation ?: return null
                val defaultScript = Paths.get(tomcatHome, "bin/catalina.sh")
                MirrordLogger.logger.debug("returning default script calculated from tomcat home: $defaultScript")
                defaultScript.toString()
            }
            MirrordLogger.logger.debug("command line is: $commandLine")
            // Split on the first space that is not preceded by a backslash.
            // 4 backslashes in the string are 1 in the regex.
            val split = commandLine.split("(?<!\\\\) ".toRegex(), limit = 2)
            val command = split.first()
            val args = split.getOrNull(1)
            MirrordLogger.logger.debug("command is: $command, args are: $args")
            CommandLineWithArgs(command, args)
        } else {
            MirrordLogger.logger.debug("tomcat set to NOT use default!")
            MirrordLogger.logger.debug("non-default script is ${scriptInfo.SCRIPT}, params are ${scriptInfo.PROGRAM_PARAMETERS}")
            CommandLineWithArgs(scriptInfo.SCRIPT, scriptInfo.PROGRAM_PARAMETERS)
        }
    }

    override fun processStartScheduled(executorId: String, env: ExecutionEnvironment) {
        MirrordLogger.logger.debug("[${this.javaClass.name}] processStartScheduled: $executorId $env")

        val config = getConfig(env) ?: run {
            MirrordLogger.logger.debug("[${this.javaClass.name}] processStartScheduled: Tomcat not detected")
            super.processStartScheduled(executorId, env)
            return
        }

        MirrordLogger.logger.debug("[${this.javaClass.name}] processStartScheduled: got config $config")
        var envVars = config.envVariables
        val envVarsMap = envVars.map { it.NAME to it.VALUE }.toMap()

        val service = env.project.service<MirrordProjectService>()

        MirrordLogger.logger.debug("[${this.javaClass.name}] processStartScheduled: wsl check")
        val wsl = when (val request = createEnvironmentRequest(env.runProfile, env.project)) {
            is WslTargetEnvironmentRequest -> request.configuration.distribution!!
            else -> null
        }

        val startupInfo = config.startupInfo
        val scriptAndArgs = if (SystemInfo.isMac) {
            val startScriptAndArgs = getStartScript(startupInfo, env)
            if (startScriptAndArgs == null) {
                MirrordLogger.logger.warn("[${this.javaClass.name}] processStartScheduled: could not get script from the configuration.")
                service.notifier.notifySimple(
                    "Mirrord could not determine the script to be run. We cannot sidestep SIP and might not load into the process.",
                    NotificationType.WARNING
                )
            }
            startScriptAndArgs
        } else {
            null
        }

        try {
            service.execManager.wrapper("idea", envVarsMap).apply {
                this.wsl = wsl
                this.executable = scriptAndArgs?.command
            }.start()?.let { executionInfo ->
                // `MIRRORD_IGNORE_DEBUGGER_PORTS` should allow clean shutdown of the app
                // even if `outgoing` feature is enabled.
                val mirrordEnv = executionInfo.environment + mapOf(Pair("MIRRORD_DETECT_DEBUGGER_PORT", "javaagent"), Pair("MIRRORD_IGNORE_DEBUGGER_PORTS", getTomcatServerPort()))

                MirrordLogger.logger.debug("[${this.javaClass.name}] processStartScheduled: saving ${envVars.size} original environment variables")
                val savedData = SavedConfigData(envVars.toList(), null)
                MirrordLogger.logger.debug("[${this.javaClass.name}] processStartScheduled: adding ${mirrordEnv.size} environment variables")
                envVars.addAll(mirrordEnv.map { (k, v) -> EnvironmentVariable(k, v, false) })

                executionInfo.envToUnset?.let { envToUnset ->
                    envVars = envVars.filter {
                        !envToUnset.contains(it.name)
                    }
                }
                config.setEnvironmentVariables(envVars)

                if (SystemInfo.isMac) {
                    MirrordLogger.logger.debug("[${this.javaClass.name}] processStartScheduled: isMac, patching SIP.")
                    executionInfo.patchedPath?.let {
                        MirrordLogger.logger.debug("[${this.javaClass.name}] processStartScheduled: patchedPath is not null: $it, meaning original was SIP")
                        savedData.scriptInfo = startupInfo
                        if (config.startupInfo.USE_DEFAULT) {
                            MirrordLogger.logger.debug("[${this.javaClass.name}] processStartScheduled: using default - handling SIP by replacing config.startupInfo")
                            val patchedStartupInfo = patchedScriptFromScript(startupInfo, it, scriptAndArgs?.args)
                            val startupInfoField = RunnerSpecificLocalConfigurationBit::class.java.getDeclaredField("myStartupInfo")
                            startupInfoField.isAccessible = true
                            startupInfoField.set(config, patchedStartupInfo)
                        } else {
                            MirrordLogger.logger.debug("[${this.javaClass.name}] processStartScheduled: NOT using default - patching by changing the non-default script")
                            config.startupInfo.SCRIPT = it
                        }
                    }
                }
                savedEnvs[executorId] = savedData
            }
        } catch (e: Throwable) {
            MirrordLogger.logger.debug("[${this.javaClass.name}] processStartScheduled: exception catched: ", e)
            // Error notifications were already fired.
            // We can't abort the execution here, so we let the app run without mirrord.
            service.notifier.notifySimple(
                "Cannot abort run due to platform limitations, running without mirrord",
                NotificationType.WARNING
            )
        }

        super.processStartScheduled(executorId, env)
    }

    private fun restoreConfig(executorId: String, config: RunnerSpecificLocalConfigurationBit) {
        MirrordLogger.logger.debug("[${this.javaClass.name}] restoreConfig: $executorId $config")

        val saved = savedEnvs.remove(executorId) ?: run {
            MirrordLogger.logger.debug("[${this.javaClass.name}] restoreConfig: no saved config data found")
            return
        }
        MirrordLogger.logger.debug("[${this.javaClass.name}] restoreConfig: found ${saved.envVars.size} saved original variables")

        config.setEnvironmentVariables(saved.envVars)

        if (SystemInfo.isMac) {
            val scriptInfo = saved.scriptInfo ?: run {
                MirrordLogger.logger.debug("[${this.javaClass.name}] restoreConfig: no saved script info found")
                return
            }

            val startupInfoField = RunnerSpecificLocalConfigurationBit::class.java.getDeclaredField("myStartupInfo")
            startupInfoField.isAccessible = true
            startupInfoField.set(config, scriptInfo)
        }
    }

    override fun processTerminating(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
        MirrordLogger.logger.debug("[${this.javaClass.name}] processTerminating: $executorId $env $handler")

        val config = getConfig(env) ?: run {
            MirrordLogger.logger.debug("[${this.javaClass.name}] processTerminating: Tomcat not detected")
            return
        }

        restoreConfig(executorId, config)

        super.processTerminating(executorId, env, handler)
    }

    override fun processNotStarted(executorId: String, env: ExecutionEnvironment) {
        MirrordLogger.logger.debug("[${this.javaClass.name}] processStarted (noop): $executorId $env")
        super.processNotStarted(executorId, env)
    }

    override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
        MirrordLogger.logger.debug("[${this.javaClass.name}] processStarted (noop): $executorId $env $handler")
        super.processStarted(executorId, env, handler)
    }
}

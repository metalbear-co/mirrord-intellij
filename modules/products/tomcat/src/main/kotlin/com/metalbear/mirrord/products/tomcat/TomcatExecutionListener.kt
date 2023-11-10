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
        val startupScript = super.getScript()
        val cmdExecutableObject = startupScript as? CommandLineExecutableObject
        val cmd = cmdExecutableObject ?: return startupScript
        val myParamsField = CommandLineExecutableObject::class.java.getDeclaredField("myParameters")
        myParamsField.isAccessible = true
        myParamsField.set(cmd, params)
        return startupScript
    }
}

/**
 * Create script object that will return a script object of SIP-patched script instead of the original.
 */
fun patchedScriptFromScript(scriptInfo: ScriptInfo, script: String, args: String?): PatchedScriptInfo {
    val argList = args?.split("(?<!\\\\) ".toRegex()) ?: emptyList()
    val params = listOf(script) + argList
    val parentField = ScriptInfo::class.java.getDeclaredField("myParent")
    val startupNotShutdownField = ScriptInfo::class.java.getDeclaredField("myStartupNotShutdown")
    parentField.isAccessible = true
    startupNotShutdownField.isAccessible = true
    val parent = parentField.get(scriptInfo) as CommonStrategy
    val startupNotShutdown = startupNotShutdownField.get(scriptInfo) as Boolean
    return PatchedScriptInfo(scriptInfo.scriptHelper, parent, startupNotShutdown, params.toTypedArray())
}

data class SavedConfigData(val envVars: List<EnvironmentVariable>, val nonDefaultScript: String?)

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
        return if (scriptInfo.USE_DEFAULT) {
//            val defaultScript = scriptInfo.script;
            val commandLine = scriptInfo.defaultScript.ifBlank {
                // We return the default script if it's not blank. If it's blank we're guessing the path on our own,
                // based on the tomcat installation location.
                val tomcatLocation =
                    (((env.runProfile as CommonStrategy).applicationServer as ApplicationServerImpl).persistentData as TomcatPersistentData).HOME
                val defaultScript = Paths.get(tomcatLocation, "bin/catalina.sh")
                defaultScript.toString()
            }
            // Split on the first space that is not preceded by a backslash.
            // 4 backslashes in the string are 1 in the regex.
            val split = commandLine.split("(?<!\\\\) ".toRegex(), limit = 2)
            val command = split.first()
            val args = split.getOrNull(1)
            CommandLineWithArgs(command, args)
        } else {
            CommandLineWithArgs(scriptInfo.SCRIPT, scriptInfo.PROGRAM_PARAMETERS)
        }
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
                    val originalScript = if (SystemInfo.isMac && !startupInfo.USE_DEFAULT) {
                        startupInfo.SCRIPT
                    } else {
                        null
                    }

                    savedEnvs[executorId] = SavedConfigData(envVars.toList(), originalScript)
                    envVars.addAll(mirrordEnv.map { (k, v) -> EnvironmentVariable(k, v, false) })
                    config.setEnvironmentVariables(envVars)

                    if (SystemInfo.isMac) {
                        patchedPath?.let {
                            if (config.startupInfo.USE_DEFAULT) {
                                val patchedStartupInfo = patchedScriptFromScript(startupInfo, it, args)
                                val startupInfoField = RunnerSpecificLocalConfigurationBit::class.java.getDeclaredField("myStartupInfo")
                                startupInfoField.isAccessible = true
                                startupInfoField.set(config, patchedStartupInfo)
                            } else {
                                config.startupInfo.SCRIPT = it
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
            saved.nonDefaultScript?.let {
                config.startupInfo.SCRIPT = it
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

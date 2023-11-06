@file:Suppress("UnstableApiUsage")

package com.metalbear.mirrord.products.tomcat

import com.intellij.execution.ExecutionListener
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.target.createEnvironmentRequest
import com.intellij.execution.util.EnvironmentVariable
import com.intellij.execution.wsl.target.WslTargetEnvironmentRequest
import com.intellij.javaee.appServers.run.configuration.RunnerSpecificLocalConfigurationBit
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.service
import com.metalbear.mirrord.CONFIG_ENV_NAME
import com.metalbear.mirrord.MirrordLogger
import com.metalbear.mirrord.MirrordProjectService
import java.util.concurrent.ConcurrentHashMap

private const val DEFAULT_TOMCAT_SERVER_PORT: String = "8005"

private fun getTomcatServerPort(): String {
    return System.getenv("MIRRORD_TOMCAT_SERVER_PORT") ?: DEFAULT_TOMCAT_SERVER_PORT
}

class TomcatExecutionListener : ExecutionListener {
    private val savedEnvs: ConcurrentHashMap<String, List<EnvironmentVariable>> = ConcurrentHashMap()

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

    override fun processStartScheduled(executorId: String, env: ExecutionEnvironment) {
        getConfig(env)?.let { config ->
            val envVars = config.envVariables

            val service = env.project.service<MirrordProjectService>()

            MirrordLogger.logger.debug("wsl check")
            val wsl = when (val request = createEnvironmentRequest(env.runProfile, env.project)) {
                is WslTargetEnvironmentRequest -> request.configuration.distribution!!
                else -> null
            }

            try {
                service.execManager.wrapper("idea").apply {
                    this.wsl = wsl
                    configFromEnv = envVars.find { e -> e.name == CONFIG_ENV_NAME }?.VALUE
                }.start()?.first?.let {
                    // `MIRRORD_IGNORE_DEBUGGER_PORTS` should allow clean shutdown of the app
                    // even if `outgoing` feature is enabled.
                    val mirrordEnv = it + mapOf(Pair("MIRRORD_DETECT_DEBUGGER_PORT", "javaagent"), Pair("MIRRORD_IGNORE_DEBUGGER_PORTS", getTomcatServerPort()))
                    savedEnvs[executorId] = envVars.toList()
                    envVars.addAll(mirrordEnv.map { (k, v) -> EnvironmentVariable(k, v, false) })
                    config.setEnvironmentVariables(envVars)
                }
            } catch (_: Throwable) {
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

    private fun restoreEnv(executorId: String, config: RunnerSpecificLocalConfigurationBit) {
        val saved = savedEnvs.remove(executorId) ?: return
        config.setEnvironmentVariables(saved)
    }

    override fun processNotStarted(executorId: String, env: ExecutionEnvironment) {
        getConfig(env)?.let {
            restoreEnv(executorId, it)
        }
        super.processNotStarted(executorId, env)
    }

    override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
        getConfig(env)?.let {
            restoreEnv(executorId, it)
        }
        super.processStarted(executorId, env, handler)
    }
}

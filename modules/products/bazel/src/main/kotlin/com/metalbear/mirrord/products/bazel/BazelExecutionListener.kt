package com.metalbear.mirrord.products.bazel

import com.intellij.execution.ExecutionListener
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.target.createEnvironmentRequest
import com.intellij.execution.wsl.target.WslTargetEnvironmentRequest
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.service
import com.metalbear.mirrord.MirrordLogger
import com.metalbear.mirrord.MirrordProjectService
import java.util.concurrent.ConcurrentHashMap
import kotlin.String

data class SavedConfigData(val envVars: Map<String, String>, val bazelPath: String?)

/**
 * The bazel execution listener is responsible for intercepting the execution of Bazel commands and
 * injecting mirrord into the process. It is also responsible for restoring the original environment
 * after the execution has finished. This is necessary because Bazel runs multiple processes, and
 * we need to make sure that the mirrord environment is only applied to the main Bazel process. It makes
 * massive use of reflection to access internal Bazel classes since they are gathered from the
 * classpath. This extreme measure is necessary because we cannot target a specific Bazel version
 * due to the fact that we need to support multiple versions of the Bazel plugin and this is not
 * possible. Newer version of the Blaze API are not compatible with the current Idea api version
 * targeted by the plugin. Moreover some of the API have been renamed in newer versions of Bazel,
 * so we need to be careful not to break the plugin on future versions.Essentially since we need to
 * be compatible with multiple versions of Bazel, we need to use reflection to access the internal
 */
class BazelExecutionListener : ExecutionListener {
    /**
     * Preserves original configuration for active Bazel runs (user environment variables and Bazel binary path)
     */
    private val savedEnvs: ConcurrentHashMap<String, SavedConfigData> = ConcurrentHashMap()
    private val bazelBinaryExecutionPlans: ConcurrentHashMap<String, BinaryExecutionPlan> = ConcurrentHashMap()

    override fun processStartScheduled(executorId: String, env: ExecutionEnvironment) {
        val service = env.project.service<MirrordProjectService>()

        MirrordLogger.logger.debug("[${this.javaClass.name}] processStartScheduled: $executorId $env")

        val binaryProvider = try {
            BazelBinaryProvider.fromExecutionEnv(env)
        } catch (e: BuildExecPlanError) {
            service.notifier.notifyRichError("mirrord plugin: ${e.message}")
            super.processStartScheduled(executorId, env)
            return
        }

        val bazelBinaryRunPlan = binaryProvider.provideTargetBinaryExecPlan(executorId) ?: run {
            MirrordLogger.logger.debug("[${this.javaClass.name}] processStartScheduled: Bazel not detected")
            super.processStartScheduled(executorId, env)
            return
        }
        bazelBinaryExecutionPlans.put(executorId, bazelBinaryRunPlan)

        val originalEnv = bazelBinaryRunPlan.getOriginalEnv()
        val binaryToPatch = bazelBinaryRunPlan.getBinaryToPatch()

        MirrordLogger.logger.debug("[${this.javaClass.name}] processStartScheduled: got run plan $bazelBinaryRunPlan")

        MirrordLogger.logger.debug("[${this.javaClass.name}] processStartScheduled: wsl check")
        @Suppress("UnstableApiUsage") // `createEnvironmentRequest`
        val wsl = when (val request = createEnvironmentRequest(env.runProfile, env.project)) {
            is WslTargetEnvironmentRequest -> request.configuration.distribution!!
            else -> null
        }

        try {
            service.execManager.wrapper("bazel", originalEnv).apply {
                this.wsl = wsl
                this.executable = binaryToPatch
            }.start()?.let { executionInfo ->
                MirrordLogger.logger.debug("[${this.javaClass.name}] processStartScheduled: adding ${executionInfo.environment.size} environment variables")
                var envVars = originalEnv + executionInfo.environment
                executionInfo.envToUnset?.let { envToUnset ->
                    envVars = envVars.filter {
                        !envToUnset.contains(it.key)
                    }
                }

                bazelBinaryRunPlan.addToEnv(envVars)

                try {
                    val originalBinary = bazelBinaryRunPlan.checkExecution(executionInfo)
                    savedEnvs[executorId] = SavedConfigData(originalEnv, originalBinary)
                } catch (e: ExecutionCheckFailed) {
                    MirrordLogger.logger.error(
                        "[${this.javaClass.name}] execution check failed, exec info : $executionInfo",
                        e
                    )
                }
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

    override fun processTerminating(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
        MirrordLogger.logger.debug("[${this.javaClass.name}] processTerminating: $executorId $env $handler")

        this.bazelBinaryExecutionPlans[executorId]?.let {
            val saved = savedEnvs.remove(executorId) ?: run {
                MirrordLogger.logger.debug("[${this.javaClass.name}] restoreConfig: no saved env found")
                return
            }
            MirrordLogger.logger.debug("[${this.javaClass.name}] removing bazel binary execution plan for $executorId, execution plan : $bazelBinaryExecutionPlans")
            this.bazelBinaryExecutionPlans.remove(executorId)
            it.restoreConfig(saved)
        }?.run {
            MirrordLogger.logger.debug("[${this.javaClass.name}] processTerminating: no execution plan found, Bazel is not detected")
            return
        }

        super.processTerminating(executorId, env, handler)
    }

    override fun processNotStarted(executorId: String, env: ExecutionEnvironment) {
        MirrordLogger.logger.debug("[${this.javaClass.name}] processNotStarted (noop): $executorId $env")
        super.processNotStarted(executorId, env)
    }

    override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
        MirrordLogger.logger.debug("[${this.javaClass.name}] processStarted (noop): $executorId $env $handler")
        super.processStarted(executorId, env, handler)
    }
}

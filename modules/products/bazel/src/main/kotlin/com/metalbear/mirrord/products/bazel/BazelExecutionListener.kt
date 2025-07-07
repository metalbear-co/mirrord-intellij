package com.metalbear.mirrord.products.bazel

import com.google.idea.blaze.base.bazel.BuildSystem
import com.google.idea.blaze.base.run.BlazeCommandRunConfiguration
import com.google.idea.blaze.base.run.state.BlazeCommandRunConfigurationCommonState
import com.google.idea.blaze.base.scope.BlazeContext
import com.google.idea.blaze.base.settings.Blaze
import com.intellij.execution.ExecutionListener
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.target.createEnvironmentRequest
import com.intellij.execution.wsl.target.WslTargetEnvironmentRequest
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.service
import com.intellij.openapi.util.SystemInfo
import com.metalbear.mirrord.MirrordLogger
import com.metalbear.mirrord.MirrordProjectService
import java.util.concurrent.ConcurrentHashMap

data class SavedConfigData(val envVars: Map<String, String>, val bazelPath: String?)

class BazelExecutionListener : ExecutionListener {
    /**
     * Preserves original configuration for active Bazel runs (user environment variables and Bazel binary path)
     */
    private val savedEnvs: ConcurrentHashMap<String, SavedConfigData> = ConcurrentHashMap()

    /**
     * Tries to unwrap Bazel-specific state from generic execution environment.
     */
    private fun getBlazeConfigurationState(env: ExecutionEnvironment): BlazeCommandRunConfigurationCommonState? {
        val runProfile = env.runProfile as? BlazeCommandRunConfiguration ?: return null
        val state = runProfile.handler.state as? BlazeCommandRunConfigurationCommonState
        return state
    }

    override fun processStartScheduled(executorId: String, env: ExecutionEnvironment) {
        val service = env.project.service<MirrordProjectService>()

        MirrordLogger.logger.debug("[${this.javaClass.name}] processStartScheduled: $executorId $env")

        val state = getBlazeConfigurationState(env) ?: run {
            MirrordLogger.logger.debug("[${this.javaClass.name}] processStartScheduled: Bazel not detected")
            super.processStartScheduled(executorId, env)
            return
        }
        MirrordLogger.logger.debug("[${this.javaClass.name}] processStartScheduled: got config state $state")

        MirrordLogger.logger.debug("[${this.javaClass.name}] processStartScheduled: wsl check")
        @Suppress("UnstableApiUsage") // `createEnvironmentRequest`
        val wsl = when (val request = createEnvironmentRequest(env.runProfile, env.project)) {
            is WslTargetEnvironmentRequest -> request.configuration.distribution!!
            else -> null
        }

        val originalEnv = state.userEnvVarsState.data.envs
        MirrordLogger.logger.debug("[${this.javaClass.name}] processStartScheduled: found ${originalEnv.size} original env variables")

        val binaryToPatch = if (SystemInfo.isMac) {
            state.blazeBinaryState.blazeBinary?.let {
                MirrordLogger.logger.debug("[${this.javaClass.name}] processStartScheduled: found Bazel binary path in the config: $it")
                it
            } ?: run {
                // Bazel binary path may be missing from the config.
                // This is the logic that Bazel plugin uses to find global Bazel binary

                val buildSystem = Blaze.getBuildSystemProvider(env.project).buildSystem
                val methods = buildSystem.javaClass.methods.filter { it.name == "getBuildInvoker" }
                var buildInvoker: BuildSystem.BuildInvoker? = null
                var usedSignature: String? = null
                for (method in methods) {
                    try {
                        val params = method.parameterTypes
                        val result = when {
                            params.size == 2 && params[0].name.contains("Project") && params[1].name.contains("BlazeContext") -> {
                                usedSignature = "getBuildInvoker(Project, BlazeContext)"
                                method.invoke(buildSystem, env.project, BlazeContext.create())
                            }
                            params.size == 1 && params[0].name.contains("Project") -> {
                                usedSignature = "getBuildInvoker(Project)"
                                method.invoke(buildSystem, env.project)
                            }
                            else -> null // Only support the two signatures above
                        }
                        buildInvoker = result as BuildSystem.BuildInvoker?
                        if (buildInvoker != null) break
                    } catch (e: Exception) {
                        // Try next signature
                    }
                }
                if (buildInvoker == null) {
                    MirrordLogger.logger.debug("[${this.javaClass.name}] Reflection: getBuildInvoker method not found or all invocations failed!")
                    null
                } else {
                    MirrordLogger.logger.debug("[${this.javaClass.name}] Reflection: Using signature: $usedSignature")
                    buildInvoker.binaryPath
                }
            }
        } else {
            null
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
                state.userEnvVarsState.setEnvVars(envVars)

                val originalBinary = state.blazeBinaryState.blazeBinary
                if (SystemInfo.isMac) {
                    executionInfo.patchedPath?.let {
                        MirrordLogger.logger.debug("[${this.javaClass.name}] processStartScheduled: patchedPath is not null: $it, meaning original was SIP")
                        state.blazeBinaryState.blazeBinary = it
                    } ?: run {
                        MirrordLogger.logger.debug("[${this.javaClass.name}] processStartScheduled: isMac, but not patching SIP (no patched path returned by the CLI).")
                    }
                }

                savedEnvs[executorId] = SavedConfigData(originalEnv, originalBinary)
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

    /**
     * Restores original configuration after Bazel run has ended.
     */
    private fun restoreConfig(executorId: String, configState: BlazeCommandRunConfigurationCommonState) {
        MirrordLogger.logger.debug("[${this.javaClass.name}] restoreEnv: $executorId $configState")

        val saved = savedEnvs.remove(executorId) ?: run {
            MirrordLogger.logger.debug("[${this.javaClass.name}] restoreConfig: no saved env found")
            return
        }

        MirrordLogger.logger.debug("[${this.javaClass.name}] restoreConfig: found ${saved.envVars.size} saved original variables")
        configState.userEnvVarsState.setEnvVars(saved.envVars)

        if (SystemInfo.isMac) {
            MirrordLogger.logger.debug("[${this.javaClass.name}] restoreConfig: found saved original Bazel path ${saved.bazelPath}")
            configState.blazeBinaryState.blazeBinary = saved.bazelPath
        }
    }

    override fun processTerminating(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
        MirrordLogger.logger.debug("[${this.javaClass.name}] processTerminating: $executorId $env $handler")

        val state = getBlazeConfigurationState(env) ?: run {
            MirrordLogger.logger.debug("[${this.javaClass.name}] processTerminating: Bazel not detected")
            return
        }

        restoreConfig(executorId, state)

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

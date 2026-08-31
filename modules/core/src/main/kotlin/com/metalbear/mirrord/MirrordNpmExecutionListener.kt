package com.metalbear.mirrord

import com.intellij.execution.ExecutionListener
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.components.service
import com.metalbear.mirrord.bifrost.MirrordEnvironment
import com.metalbear.mirrord.bifrost.MirrordEnvironments
import kotlinx.coroutines.CancellationException

data class RunConfigGuard(val executionId: Long) {
    var originEnv: Map<String, String> = LinkedHashMap()
    var originPackageManagerPackageRef: Any? = null
}

class MirrordNpmExecutionListener : ExecutionListener {

    companion object {
        val executions: MutableMap<Long, RunConfigGuard> = LinkedHashMap()
    }

    private fun detectNpmRunConfiguration(env: ExecutionEnvironment): Boolean {
        return env.runProfile::class.qualifiedName == "com.intellij.lang.javascript.buildTools.npm.rc.NpmRunConfiguration"
    }

    private fun patchNpmEnv(environment: MirrordEnvironment, env: ExecutionEnvironment) {
        val service = env.project.service<MirrordProjectService>()

        val executionGuard = executions[env.executionId]!!

        try {
            val runSettings = MirrordNpmMutableRunSettings.fromRunProfile(env.project, env.runProfile)

            // macOS strips DYLD_INSERT_LIBRARIES from signed binaries, so the CLI has to
            // re-sign a copy. Keyed on the *target* now, not the IDE host: a Mac driving a Linux
            // container needs no patching, and a Linux IDE driving a macOS target does.
            val executablePath = if (environment.platform().isMac) {
                runSettings.packageManagerPackagePath
            } else {
                null
            }

            executionGuard.originEnv = LinkedHashMap(runSettings.envs)

            service.execManager.wrapper("JS", executionGuard.originEnv, environment).apply {
                executable = executablePath
            }.start()?.let { executionInfo ->
                var envs = (executionGuard.originEnv + executionInfo.environment)

                executionInfo.envToUnset?.let { envToUnset ->
                    envs = envs.filterKeys { !envToUnset.contains(it) }
                }
                runSettings.envs = envs

                executionInfo.patchedPath?.let {
                    executionGuard.originPackageManagerPackageRef = runSettings.packageManagerPackageRef
                    runSettings.packageManagerPackagePath = it
                }
            }
        } catch (e: CancellationException) {
            // The user pressed Cancel. Let it travel; the platform aborts the launch quietly.
            throw e
        } catch (e: Exception) {
            MirrordLogger.logger.error("mirrord failed to patch npm run: $e")
            service.notifier.notifyRichError("mirrord failed to patch npm run")
        }
    }

    private fun clearNpmEnv(env: ExecutionEnvironment) {
        val executionGuard = executions[env.executionId]!!
        val runSettings = MirrordNpmMutableRunSettings.fromRunProfile(env.project, env.runProfile)

        try {
            runSettings.envs = executionGuard.originEnv

            // No host check. `originPackageManagerPackageRef` is set only for a macOS target,
            // so the null check below is the complete gate.
            executionGuard.originPackageManagerPackageRef?.let {
                runSettings.packageManagerPackageRef = it
            }
        } catch (e: CancellationException) {
            // Cleanup runs in `finally`, so the run configuration is still restored.
            throw e
        } catch (e: Exception) {
            MirrordLogger.logger.error("mirrord failed to clear npm run patch: $e")
            val service = env.project.service<MirrordProjectService>()
            service.notifier.notifyRichError("mirrord failed to clear npm run patch")
        } finally {
            executions.remove(env.executionId)
        }
    }

    override fun processStarting(executorId: String, env: ExecutionEnvironment) {
        val service = env.project.service<MirrordProjectService>()

        if (!service.enabled || !this.detectNpmRunConfiguration(env)) {
            return super.processStarting(executorId, env)
        }

        executions[env.executionId] = RunConfigGuard(env.executionId)

        val environment = MirrordEnvironments.forRunProfile(env.project, env.runProfile)

        patchNpmEnv(environment, env)

        super.processStartScheduled(executorId, env)
    }

    override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
        if (this.detectNpmRunConfiguration(env) && executions.containsKey(env.executionId)) {
            clearNpmEnv(env)
        }

        super.processStarted(executorId, env, handler)
    }

    override fun processNotStarted(executorId: String, env: ExecutionEnvironment) {
        if (this.detectNpmRunConfiguration(env) && executions.containsKey(env.executionId)) {
            clearNpmEnv(env)
        }

        super.processNotStarted(executorId, env)
    }

    override fun processTerminating(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
        if (this.detectNpmRunConfiguration(env) && executions.containsKey(env.executionId)) {
            clearNpmEnv(env)
        }

        return super.processTerminating(executorId, env, handler)
    }
}

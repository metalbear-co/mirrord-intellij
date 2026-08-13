package com.metalbear.mirrord

import com.intellij.execution.ExecutionListener
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.target.createEnvironmentRequest
import com.intellij.openapi.components.service
import com.intellij.openapi.util.SystemInfo
import com.metalbear.mirrord.bifrost.MirrordEnvironment
import com.metalbear.mirrord.bifrost.MirrordEnvironments
import com.metalbear.mirrord.bifrost.MirrordLaunchContext

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

            if (SystemInfo.isMac) {
                executionGuard.originPackageManagerPackageRef?.let {
                    runSettings.packageManagerPackageRef = it
                }
            }
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

        val environment = MirrordEnvironments.resolve(
            MirrordLaunchContext(env.project, createEnvironmentRequest(env.runProfile, env.project))
        )

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

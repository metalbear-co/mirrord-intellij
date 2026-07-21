@file:Suppress("UnstableApiUsage")

package com.metalbear.mirrord.products.idea

import com.intellij.execution.ExecutionListener
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.target.createEnvironmentRequest
import com.intellij.execution.wsl.target.WslTargetEnvironmentRequest
import com.intellij.openapi.components.service
import com.metalbear.mirrord.MirrordLogger
import com.metalbear.mirrord.MirrordProjectService

class IdeaExecutionListener : ExecutionListener {
    override fun processStartScheduled(
        executorId: String,
        env: ExecutionEnvironment,
    ) {
        val configuration =
            env.runProfile as? RunConfigurationBase<*> ?: run {
                MirrordLogger.logger.debug(
                    "[${this.javaClass.name}] processStartScheduled: unsupported run profile `${env.runProfile.javaClass.name}`",
                )
                return
            }

        if (!isIdeaConfigurationApplicableForMirrord(configuration)) {
            MirrordLogger.logger.debug("[${this.javaClass.name}] processStartScheduled: skipped `${configuration.name}`")
            return
        }

        val alreadyContainsInitTask = configuration.beforeRunTasks.any { it is IdeaBeforeRunTaskProvider.IdeaBeforeRunTask }

        if (!alreadyContainsInitTask) {
            configuration.beforeRunTasks = configuration.beforeRunTasks +
                IdeaBeforeRunTaskProvider.IdeaBeforeRunTask {
                    val service = env.project.service<MirrordProjectService>()
                    val wsl =
                        when (val request = createEnvironmentRequest(env.runProfile, env.project)) {
                            is WslTargetEnvironmentRequest -> request.configuration.distribution
                            else -> null
                        }

                    service.execManager
                        .wrapper("idea", getIdeaConfigurationEnv(configuration))
                        .apply {
                            this.wsl = wsl
                        }.start()
                        ?.let { executionInfo ->
                            IdeaMirrordPreparationStore.put(configuration, executionInfo)
                        }
                }
        }

        super.processStartScheduled(executorId, env)
    }

    override fun processTerminated(
        executorId: String,
        env: ExecutionEnvironment,
        handler: ProcessHandler,
        exitCode: Int,
    ) {
        clearState(env)
        super.processTerminated(executorId, env, handler, exitCode)
    }

    override fun processNotStarted(
        executorId: String,
        env: ExecutionEnvironment,
    ) {
        clearState(env)
        super.processNotStarted(executorId, env)
    }

    private fun clearState(env: ExecutionEnvironment) {
        val configuration = env.runProfile as? RunConfigurationBase<*> ?: return
        IdeaMirrordPreparationStore.clear(configuration)
        configuration.beforeRunTasks = configuration.beforeRunTasks.filter { it !is IdeaBeforeRunTaskProvider.IdeaBeforeRunTask }
    }
}

package com.metalbear.mirrord.products.idea

import com.intellij.execution.BeforeRunTask
import com.intellij.execution.BeforeRunTaskProvider
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.util.Key

val IDEA_BEFORE_RUN_KEY: Key<IdeaBeforeRunTaskProvider.IdeaBeforeRunTask> = Key("MirrordIdeaBeforeRunTask")

class IdeaBeforeRunTaskProvider : BeforeRunTaskProvider<IdeaBeforeRunTaskProvider.IdeaBeforeRunTask>() {
    /**
     * Simple task that only executes the given callback.
     * Must throw an [Exception] in case execution should be stopped.
     */
    class IdeaBeforeRunTask(val callback: () -> Unit) : BeforeRunTask<IdeaBeforeRunTask>(IDEA_BEFORE_RUN_KEY)

    override fun getId(): Key<IdeaBeforeRunTask> = IDEA_BEFORE_RUN_KEY

    override fun getName(): String = "IdeaBeforeRunTask"

    /**
     * Always returns null. Otherwise, the task would be visible in the run configuration UI.
     */
    override fun createTask(runConfiguration: RunConfiguration): IdeaBeforeRunTask? {
        return null
    }

    /**
     * Returning `false` here prevents the execution.
     */
    override fun executeTask(
        context: DataContext,
        configuration: RunConfiguration,
        environment: ExecutionEnvironment,
        task: IdeaBeforeRunTask
    ): Boolean {
        return try {
            task.callback.invoke()
            true
        } catch (_: Exception) {
            // Exceptions already handled in `MirrordExecManager`.
            false
        }
    }
}

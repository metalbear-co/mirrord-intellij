package com.metalbear.mirrord.products.tomcat

import com.intellij.execution.BeforeRunTask
import com.intellij.execution.BeforeRunTaskProvider
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.util.Key

val KEY: Key<TomcatBeforeRunTaskProvider.TomcatBeforeRunTask> = Key("MirrordTomcatBeforeRunTask")

class TomcatBeforeRunTaskProvider : BeforeRunTaskProvider<TomcatBeforeRunTaskProvider.TomcatBeforeRunTask>() {
    /**
     * Simple task that only executes the given callback.
     * Must throw an [Exception] in case execution should be stopped.
     */
    class TomcatBeforeRunTask(val callback: () -> Unit) : BeforeRunTask<TomcatBeforeRunTask>(KEY)

    override fun getId(): Key<TomcatBeforeRunTask> = KEY

    override fun getName(): String = "TomcatBeforeRunTask"

    /**
     * Always returns null. Otherwise, the task would be visible to the users in the run configuration dialog when they add their own before launch tasks.
     */
    override fun createTask(runConfiguration: RunConfiguration): TomcatBeforeRunTask? {
        return null
    }

    /**
     * Returning `false` here prevents the execution.
     */
    override fun executeTask(
        context: DataContext,
        configuration: RunConfiguration,
        environment: ExecutionEnvironment,
        task: TomcatBeforeRunTask
    ): Boolean {
        return try {
            task.callback.invoke()
            true
        } catch (_: Exception) {
            // Exceptions already handled in `MirrordExecManager`
            false
        }
    }
}

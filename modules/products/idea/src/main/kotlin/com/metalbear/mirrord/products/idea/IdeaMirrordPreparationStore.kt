package com.metalbear.mirrord.products.idea

import com.intellij.execution.configurations.RunConfigurationBase
import com.metalbear.mirrord.MirrordExecution
import com.metalbear.mirrord.bifrost.MirrordEnvironment
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * What the before-run task prepared: the mirrord execution, and the environment it ran in.
 *
 * The environment travels with the execution because `updateJavaParameters` runs under a read
 * lock and cannot resolve one itself.
 *
 * It also has no access to the run configuration's target request, so resolving there would
 * pick a different environment than the one `mirrord ext` ran in.
 */
internal data class IdeaMirrordPreparation(
    val execution: MirrordExecution,
    val environment: MirrordEnvironment
)

/**
 * Stores mirrord execution info prepared in before-run tasks until Java parameters are created.
 */
internal object IdeaMirrordPreparationStore {
    private val queueByConfiguration = ConcurrentHashMap<RunConfigurationBase<*>, ConcurrentLinkedQueue<IdeaMirrordPreparation>>()

    fun put(configuration: RunConfigurationBase<*>, execution: MirrordExecution, environment: MirrordEnvironment) {
        queueByConfiguration.computeIfAbsent(configuration) { ConcurrentLinkedQueue() }
            .add(IdeaMirrordPreparation(execution, environment))
    }

    fun consume(configuration: RunConfigurationBase<*>): IdeaMirrordPreparation? {
        val queue = queueByConfiguration[configuration] ?: return null
        val preparation = queue.poll()
        if (queue.isEmpty()) {
            queueByConfiguration.remove(configuration, queue)
        }
        return preparation
    }

    fun clear(configuration: RunConfigurationBase<*>) {
        queueByConfiguration.remove(configuration)
    }
}

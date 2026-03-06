package com.metalbear.mirrord.products.idea

import com.intellij.execution.configurations.RunConfigurationBase
import com.metalbear.mirrord.MirrordExecution
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Stores mirrord execution info prepared in before-run tasks until Java parameters are created.
 */
internal object IdeaMirrordPreparationStore {
    private val queueByConfiguration = ConcurrentHashMap<RunConfigurationBase<*>, ConcurrentLinkedQueue<MirrordExecution>>()

    fun put(configuration: RunConfigurationBase<*>, execution: MirrordExecution) {
        queueByConfiguration.computeIfAbsent(configuration) { ConcurrentLinkedQueue() }.add(execution)
    }

    fun consume(configuration: RunConfigurationBase<*>): MirrordExecution? {
        val queue = queueByConfiguration[configuration] ?: return null
        val execution = queue.poll()
        if (queue.isEmpty()) {
            queueByConfiguration.remove(configuration, queue)
        }
        return execution
    }

    fun clear(configuration: RunConfigurationBase<*>) {
        queueByConfiguration.remove(configuration)
    }
}

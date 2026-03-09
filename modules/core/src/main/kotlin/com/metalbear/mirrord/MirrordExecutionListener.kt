package com.metalbear.mirrord

import com.intellij.execution.ExecutionListener
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment

/**
 * Single execution-listener entry point.
 *
 * Routes each execution to the relevant product delegate and invokes only the required callback.
 * Delegates are loaded reflectively so optional product dependencies remain optional.
 */
class MirrordExecutionListener : ExecutionListener {
    companion object {
        private const val NPM_RUN_PROFILE = "com.intellij.lang.javascript.buildTools.npm.rc.NpmRunConfiguration"
        private const val BAZEL_RUN_PROFILE_SUFFIX = "BlazeCommandRunConfiguration"
        private const val RUNNER_SPECIFIC_LOCAL_CONFIGURATION_BIT =
            "com.intellij.javaee.appServers.run.configuration.RunnerSpecificLocalConfigurationBit"
        private const val EXTERNAL_SYSTEM_RUN_CONFIGURATION =
            "com.intellij.openapi.externalSystem.service.execution.ExternalSystemRunConfiguration"
        private const val COMMON_JAVA_RUN_CONFIGURATION_PARAMETERS =
            "com.intellij.execution.CommonJavaRunConfigurationParameters"
    }

    private enum class DelegateKind {
        NPM, IDEA, TOMCAT, BAZEL
    }

    private enum class ExecutionKind {
        NPM, BAZEL, TOMCAT, IDEA, OTHER
    }

    private val delegateClassNames = mapOf(
        DelegateKind.NPM to "com.metalbear.mirrord.MirrordNpmExecutionListener",
        DelegateKind.IDEA to "com.metalbear.mirrord.products.idea.IdeaExecutionListener",
        DelegateKind.TOMCAT to "com.metalbear.mirrord.products.tomcat.TomcatExecutionListener",
        DelegateKind.BAZEL to "com.metalbear.mirrord.products.bazel.BazelExecutionListener"
    )

    private val delegates: Map<DelegateKind, ExecutionListener> by lazy {
        delegateClassNames.mapNotNull { (kind, className) ->
            createDelegate(className)?.let { kind to it }
        }.toMap()
    }

    private fun createDelegate(className: String): ExecutionListener? {
        return try {
            val clazz = javaClass.classLoader.loadClass(className)
            val instance = clazz.getDeclaredConstructor().newInstance()
            val listener = instance as? ExecutionListener
            if (listener == null) {
                MirrordLogger.logger.warn("Execution listener delegate `$className` does not implement ExecutionListener")
            }
            listener
        } catch (_: Throwable) {
            // Expected when optional product dependencies are absent.
            null
        }
    }

    private fun dispatch(kind: DelegateKind, actionName: String, action: (ExecutionListener) -> Unit) {
        val delegate = delegates[kind] ?: return

        try {
            action(delegate)
        } catch (e: Throwable) {
            MirrordLogger.logger.warn("Execution listener delegate `${delegate.javaClass.name}` failed in `$actionName`: ${e.message}", e)
        }
    }

    private fun resolveExecutionKind(env: ExecutionEnvironment): ExecutionKind {
        val runProfileClassName = env.runProfile.javaClass.name
        if (runProfileClassName == NPM_RUN_PROFILE) {
            return ExecutionKind.NPM
        }

        if (runProfileClassName.endsWith(BAZEL_RUN_PROFILE_SUFFIX)) {
            return ExecutionKind.BAZEL
        }

        val settingsClassName = env.configurationSettings?.javaClass?.name.orEmpty()
        if (runProfileClassName.contains("tomcat", ignoreCase = true) ||
            settingsClassName.contains("tomcat", ignoreCase = true) ||
            settingsClassName == RUNNER_SPECIFIC_LOCAL_CONFIGURATION_BIT
        ) {
            return ExecutionKind.TOMCAT
        }

        if (runProfileClassName == EXTERNAL_SYSTEM_RUN_CONFIGURATION ||
            implementsInterface(env.runProfile.javaClass, COMMON_JAVA_RUN_CONFIGURATION_PARAMETERS)
        ) {
            return ExecutionKind.IDEA
        }

        return ExecutionKind.OTHER
    }

    private fun implementsInterface(clazz: Class<*>, interfaceName: String): Boolean {
        var current: Class<*>? = clazz
        while (current != null) {
            if (current.interfaces.any { it.name == interfaceName || implementsInterface(it, interfaceName) }) {
                return true
            }
            current = current.superclass
        }
        return false
    }

    override fun processStartScheduled(executorId: String, env: ExecutionEnvironment) {
        when (resolveExecutionKind(env)) {
            ExecutionKind.BAZEL -> dispatch(DelegateKind.BAZEL, "processStartScheduled") {
                it.processStartScheduled(executorId, env)
            }
            ExecutionKind.TOMCAT -> dispatch(DelegateKind.TOMCAT, "processStartScheduled") {
                it.processStartScheduled(executorId, env)
            }
            ExecutionKind.IDEA -> dispatch(DelegateKind.IDEA, "processStartScheduled") {
                it.processStartScheduled(executorId, env)
            }
            else -> {}
        }
        super.processStartScheduled(executorId, env)
    }

    override fun processStarting(executorId: String, env: ExecutionEnvironment) {
        if (resolveExecutionKind(env) == ExecutionKind.NPM) {
            dispatch(DelegateKind.NPM, "processStarting") {
                it.processStarting(executorId, env)
            }
        }
        super.processStarting(executorId, env)
    }

    override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
        if (resolveExecutionKind(env) == ExecutionKind.NPM) {
            dispatch(DelegateKind.NPM, "processStarted") {
                it.processStarted(executorId, env, handler)
            }
        }
        super.processStarted(executorId, env, handler)
    }

    override fun processNotStarted(executorId: String, env: ExecutionEnvironment) {
        when (resolveExecutionKind(env)) {
            ExecutionKind.NPM -> dispatch(DelegateKind.NPM, "processNotStarted") {
                it.processNotStarted(executorId, env)
            }
            ExecutionKind.TOMCAT -> dispatch(DelegateKind.TOMCAT, "processNotStarted") {
                it.processNotStarted(executorId, env)
            }
            ExecutionKind.IDEA -> dispatch(DelegateKind.IDEA, "processNotStarted") {
                it.processNotStarted(executorId, env)
            }
            else -> {}
        }
        super.processNotStarted(executorId, env)
    }

    override fun processTerminating(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
        when (resolveExecutionKind(env)) {
            ExecutionKind.NPM -> dispatch(DelegateKind.NPM, "processTerminating") {
                it.processTerminating(executorId, env, handler)
            }
            ExecutionKind.BAZEL -> dispatch(DelegateKind.BAZEL, "processTerminating") {
                it.processTerminating(executorId, env, handler)
            }
            else -> {}
        }
        super.processTerminating(executorId, env, handler)
    }

    override fun processTerminated(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler, exitCode: Int) {
        when (resolveExecutionKind(env)) {
            ExecutionKind.TOMCAT -> dispatch(DelegateKind.TOMCAT, "processTerminated") {
                it.processTerminated(executorId, env, handler, exitCode)
            }
            ExecutionKind.IDEA -> dispatch(DelegateKind.IDEA, "processTerminated") {
                it.processTerminated(executorId, env, handler, exitCode)
            }
            else -> {}
        }
        super.processTerminated(executorId, env, handler, exitCode)
    }
}

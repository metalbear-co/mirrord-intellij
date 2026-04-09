package com.metalbear.mirrord.products.idea

import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.components.service
import com.metalbear.mirrord.MirrordExecution
import com.metalbear.mirrord.MirrordLogger
import com.metalbear.mirrord.MirrordLogsService
import com.metalbear.mirrord.MirrordProjectService
import org.jetbrains.sbt.runner.SbtRunConfiguration
import java.util.SortedMap
import java.util.TreeMap

private const val MIRRORD_SBT_RUN_TASK = "run"
private const val MIRRORD_SBT_FG_RUN_TASK = "fgRun"
private const val MIRRORD_SBT_RUN_MAIN_TASK = "runMain"
private const val MIRRORD_SBT_COMMAND_SEPARATOR = ";"
private const val MIRRORD_SBT_RUN_CONFIGURATION = "com.metalbear.mirrord.products.idea.MirrordSbtRunConfiguration"

/**
 * Custom SBT run configuration that always executes through the Scala plugin's SBT shell path.
 *
 * We do not rely on IntelliJ's generic Java run configuration extension flow here. Instead:
 * 1. `getState` prepares a mirrord execution eagerly for this launch.
 * 2. `preprocessTasks` rewrites supported SBT application tasks into a chained shell command that
 *    sets `fork` and `envVars` for the task scope before running it.
 *
 * This keeps the integration self-contained for shell-backed SBT execution, where
 * `updateJavaParameters` is not a reliable hook.
 */
class MirrordSbtRunConfiguration(
    project: com.intellij.openapi.project.Project,
    configurationFactory: com.intellij.execution.configurations.ConfigurationFactory,
    name: String
) : SbtRunConfiguration(project, configurationFactory, name) {
    @Volatile
    private var pendingMirrordExecution: MirrordExecution? = null

    init {
        setUseSbtShell(true)
    }

    override fun getUseSbtShell(): Boolean = true

    override fun setUseSbtShell(value: Boolean) {
        if (!value) {
            logWarningToUser("mirrord SBT only supports running through the SBT shell. Disabling `useSbtShell` is ignored.")
        }
        super.setUseSbtShell(true)
    }

    override fun getState(executor: Executor, env: ExecutionEnvironment): org.jetbrains.sbt.runner.SbtCommandLineState {
        pendingMirrordExecution = prepareMirrordExecution()
        return super.getState(executor, env) as org.jetbrains.sbt.runner.SbtCommandLineState
    }

    /**
     * The Scala plugin calls `preprocessTasks` before submitting shell commands.
     * We use that moment to replace a supported app task with a mirrord-aware chained shell command.
     */
    override fun preprocessTasks(): String {
        val processedCommands = buildMirrordAwareCommands()
        MirrordLogger.logger.debug(
            "[${this.javaClass.name}] preprocessTasks: configuration=`${name}`, commands=`$processedCommands`"
        )
        return processedCommands
    }

    private fun buildMirrordAwareCommands(): String {
        val originalCommands = super.preprocessTasks()
        val executionInfo = pendingMirrordExecution.also { pendingMirrordExecution = null } ?: return originalCommands

        val taskScope = resolveTaskScope(originalCommands) ?: run {
            MirrordLogger.logger.warn(
                "[${this.javaClass.name}] buildMirrordAwareCommands: unsupported SBT task `${originalCommands}` for `${name}`, running without shell env injection"
            )
            logWarningToUser(
                "mirrord SBT currently supports only simple `run`, `runMain`, and `fgRun` shell tasks. " +
                    "Task `${originalCommands}` will run without mirrord shell env injection."
            )
            return originalCommands
        }

        val originalEnv = getSbtConfigurationEnv(this)
        val targetEnv = originalEnv +
            executionInfo.environment +
            mapOf("MIRRORD_DETECT_DEBUGGER_PORT" to "javaagent") -
            executionInfo.envToUnset.orEmpty().toSet()

        val envMapLiteral = toSbtMapLiteral(targetEnv)
        return buildString {
            append(MIRRORD_SBT_COMMAND_SEPARATOR)
            append("set ")
            append(taskScope)
            append(" / fork := true")
            append(MIRRORD_SBT_COMMAND_SEPARATOR)
            append("set ")
            append(taskScope)
            append(" / envVars := ")
            append(envMapLiteral)
            append(MIRRORD_SBT_COMMAND_SEPARATOR)
            append(originalCommands)
        }
    }

    /**
     * Maps a shell command to the SBT task scope whose `fork` and `envVars` settings should be changed.
     *
     * We only support simple single-command app launches. Chained commands and watcher commands are left untouched.
     */
    private fun resolveTaskScope(commands: String): String? {
        val command = commands.trim()
        if (command.isEmpty() || command.startsWith(MIRRORD_SBT_COMMAND_SEPARATOR) || command.contains(MIRRORD_SBT_COMMAND_SEPARATOR)) {
            return null
        }

        return when {
            command == MIRRORD_SBT_RUN_TASK -> "Compile / run"
            command.startsWith("$MIRRORD_SBT_RUN_MAIN_TASK ") -> "Compile / runMain"
            command == MIRRORD_SBT_RUN_MAIN_TASK -> "Compile / runMain"
            command.endsWith("/$MIRRORD_SBT_RUN_TASK") -> command
            command.endsWith("/$MIRRORD_SBT_RUN_MAIN_TASK") -> command
            command == MIRRORD_SBT_FG_RUN_TASK -> MIRRORD_SBT_FG_RUN_TASK
            command.endsWith("/$MIRRORD_SBT_FG_RUN_TASK") -> command
            else -> null
        }
    }

    private fun toSbtMapLiteral(env: Map<String, String>): String {
        val sortedEnv: SortedMap<String, String> = TreeMap(env)
        return sortedEnv.entries.joinToString(
            prefix = "Map(",
            postfix = ")",
            separator = ", "
        ) { (key, value) ->
            "\"${escapeSbtString(key)}\" -> \"${escapeSbtString(value)}\""
        }
    }

    private fun escapeSbtString(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")

    /**
     * Prepares mirrord before the Scala plugin asks us for the final shell command string.
     *
     * This is intentionally eager because shell-backed SBT execution does not reliably invoke the generic
     * Java parameter extension path used by regular JVM run configurations.
     */
    private fun prepareMirrordExecution(): MirrordExecution? {
        val originalCommands = super.preprocessTasks()
        val taskScope = resolveTaskScope(originalCommands) ?: run {
            logWarningToUser(
                "mirrord SBT currently supports only simple `run`, `runMain`, and `fgRun` shell tasks. " +
                    "Task `${originalCommands}` is not supported."
            )
            return null
        }

        MirrordLogger.logger.debug(
            "[${this.javaClass.name}] prepareMirrordExecution: preparing mirrord for `${name}` taskScope=`$taskScope`"
        )

        val service = project.service<MirrordProjectService>()
        return service.execManager.wrapper("idea", getSbtConfigurationEnv(this)).start()
    }

    private fun logWarningToUser(message: String) {
        project.service<MirrordLogsService>().logWarning(message)
    }

    private fun getSbtConfigurationEnv(configuration: RunConfigurationBase<*>): Map<String, String> {
        return try {
            if (configuration.javaClass.name == MIRRORD_SBT_RUN_CONFIGURATION) {
                @Suppress("UNCHECKED_CAST")
                configuration.javaClass.getMethod("environmentVariables").invoke(configuration) as? Map<String, String> ?: emptyMap()
            } else {
                emptyMap()
            }
        } catch (e: ReflectiveOperationException) {
            MirrordLogger.logger.warn(
                "Failed reading SBT run configuration environment for `${configuration.name}`: ${e.message}",
                e
            )
            emptyMap()
        }
    }
}

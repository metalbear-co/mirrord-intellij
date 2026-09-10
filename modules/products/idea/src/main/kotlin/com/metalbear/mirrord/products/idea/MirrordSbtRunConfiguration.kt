package com.metalbear.mirrord.products.idea

import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.components.service
import com.intellij.util.execution.ParametersListUtil
import com.metalbear.mirrord.MirrordExecution
import com.metalbear.mirrord.MirrordLogger
import com.metalbear.mirrord.MirrordLogsService
import com.metalbear.mirrord.MirrordProjectService
import com.metalbear.mirrord.bifrost.MirrordEnvironments
import org.jetbrains.sbt.runner.SbtCommandLineState
import org.jetbrains.sbt.runner.SbtRunConfiguration
import java.lang.reflect.Method
import java.nio.file.Files
import java.nio.file.Path
import java.util.SortedMap
import java.util.TreeMap

private const val MIRRORD_SBT_RUN_TASK = "run"
private const val MIRRORD_SBT_FG_RUN_TASK = "fgRun"
private const val MIRRORD_SBT_RUN_MAIN_TASK = "runMain"
private const val MIRRORD_SBT_COMMAND_SEPARATOR = ";"
private const val MIRRORD_SBT_RUN_CONFIGURATION = "com.metalbear.mirrord.products.idea.MirrordSbtRunConfiguration"

/**
 * Custom SBT run configuration that supports both Scala-plugin execution modes.
 *
 * We do not rely on IntelliJ's generic Java run configuration extension flow here.
 *
 * For `useSbtShell=true`, `preprocessTasks` rewrites supported SBT application tasks into a chained
 * shell command that sets `fork` and `envVars` for the task scope before running it.
 *
 * For `useSbtShell=false`, we inject mirrord env directly into the run configuration's environment
 * map before the Scala plugin copies it into `JavaParameters`.
 */
class MirrordSbtRunConfiguration(
    project: com.intellij.openapi.project.Project,
    configurationFactory: com.intellij.execution.configurations.ConfigurationFactory,
    name: String
) : SbtRunConfiguration(project, configurationFactory, name) {
    @Volatile
    private var pendingMirrordExecution: MirrordExecution? = null

    override fun getState(executor: Executor, env: ExecutionEnvironment): SbtCommandLineState {
        val executionInfo = prepareMirrordExecution() ?: return super.getState(executor, env) as SbtCommandLineState

        return if (useSbtShell) {
            createShellLaunchState(executionInfo, executor, env)
        } else {
            createDirectLaunchState(executionInfo, executor, env)
        }
    }

    /**
     * Replaces a supported app task with a mirrord-aware chained shell command.
     *
     * This is the injection point on Scala 2026.1 and older, where the plugin calls `preprocessTasks`
     * while building the launch. Newer plugins never call it -- [createShellLaunchState] writes the
     * command in for those.
     */
    override fun preprocessTasks(): String {
        if (!useSbtShell) {
            return preprocessedTasks()
        }

        val processedCommands = buildMirrordAwareCommands(preprocessedTasks())
        MirrordLogger.logger.debug(
            "[${this.javaClass.name}] preprocessTasks: configuration=`$name`, commands=`$processedCommands`"
        )
        return processedCommands
    }

    private fun buildMirrordAwareCommands(originalCommands: String): String {
        val executionInfo = pendingMirrordExecution.also { pendingMirrordExecution = null } ?: return originalCommands

        return buildMirrordSbtShellCommands(
            originalCommands = originalCommands,
            configurationEnv = getSbtConfigurationEnv(this),
            mirrordEnv = executionInfo.environment,
            envToUnset = executionInfo.envToUnset.orEmpty().toSet()
        ) ?: run {
            MirrordLogger.logger.warn(
                "[${this.javaClass.name}] buildMirrordAwareCommands: unsupported SBT task `$originalCommands` for `$name`, running without shell env injection"
            )
            logWarningToUser(
                "mirrord SBT currently supports only simple `run`, `runMain`, and `fgRun` shell tasks. " +
                    "Task `$originalCommands` will run without mirrord shell env injection."
            )
            originalCommands
        }
    }

    /**
     * Prepares mirrord before delegating to either SBT shell execution or direct SBT launch.
     *
     * This is intentionally eager because SBT execution does not reliably pass through the generic
     * Java parameter extension hook used by regular JVM run configurations.
     */
    private fun prepareMirrordExecution(): MirrordExecution? {
        if (useSbtShell) {
            maybeWarnAboutPlayProject()

            val originalCommands = originalShellCommands()
            val taskScope = resolveSbtTaskScope(originalCommands) ?: run {
                logWarningToUser(
                    "mirrord SBT currently supports only simple `run`, `runMain`, and `fgRun` shell tasks. " +
                        "Task `$originalCommands` is not supported."
                )
                return null
            }

            MirrordLogger.logger.debug(
                "[${this.javaClass.name}] prepareMirrordExecution: preparing mirrord for `$name` taskScope=`$taskScope`"
            )
        } else {
            MirrordLogger.logger.debug(
                "[${this.javaClass.name}] prepareMirrordExecution: preparing mirrord for direct SBT launch `$name`"
            )
        }

        val service = project.service<MirrordProjectService>()
        val environment = MirrordEnvironments.forProject(project)
        return service.execManager.wrapper("idea", getSbtConfigurationEnv(this), environment).start()
    }

    /**
     * Builds the state for an SBT *shell* launch, with mirrord's command rewrite in place.
     *
     * The Scala plugin changed where it takes that command from. Up to 2026.1 `getState` called
     * `preprocessTasks` while constructing the state, so [preprocessTasks] was enough of a hook. From
     * 2026.2 it reads the stored `commands` string instead and never calls `preprocessTasks` at all.
     * So on the newer plugin the command is written into the configuration before delegating,
     * and put back straight after.
     *
     * Restoring immediately is safe: `SbtCommandLineState` copies the string in its constructor and
     * never reads the configuration again, so the run keeps mirrord's command while what the user
     * sees in their settings stays their own.
     */
    private fun createShellLaunchState(
        executionInfo: MirrordExecution,
        executor: Executor,
        env: ExecutionEnvironment
    ): SbtCommandLineState {
        pendingMirrordExecution = executionInfo

        // Older plugin: `preprocessTasks` is still the hook, and it consumes pendingMirrordExecution.
        val storedCommands = SbtStoredCommands.of(this)
            ?: return super.getState(executor, env) as SbtCommandLineState

        val originalCommands = storedCommands.value
        return try {
            val mirrordCommands = buildMirrordAwareCommands(originalCommands)
            MirrordLogger.logger.debug(
                "[${this.javaClass.name}] createShellLaunchState: configuration=`$name`, commands=`$mirrordCommands`"
            )
            storedCommands.value = mirrordCommands
            super.getState(executor, env) as SbtCommandLineState
        } finally {
            storedCommands.value = originalCommands
            pendingMirrordExecution = null
        }
    }

    private fun originalShellCommands(): String = SbtStoredCommands.of(this)?.value ?: preprocessedTasks()

    private fun createDirectLaunchState(
        executionInfo: MirrordExecution,
        executor: Executor,
        env: ExecutionEnvironment
    ): SbtCommandLineState {
        val originalEnv = HashMap(environmentVariables())
        val injectedEnv = originalEnv +
            executionInfo.environment +
            mapOf("MIRRORD_DETECT_DEBUGGER_PORT" to "javaagent") -
            executionInfo.envToUnset.orEmpty().toSet()

        environmentVariables().clear()
        environmentVariables().putAll(injectedEnv)

        val state = try {
            super.getState(executor, env) as SbtCommandLineState
        } catch (t: Throwable) {
            restoreEnvironment(originalEnv)
            throw t
        }

        restoreEnvironmentWhenLaunchEnds(env, originalEnv)
        return state
    }

    private fun restoreEnvironmentWhenLaunchEnds(env: ExecutionEnvironment, originalEnv: Map<String, String>) {
        val connection = project.messageBus.connect(project)

        connection.subscribe(
            ExecutionManager.EXECUTION_TOPIC,
            object : ExecutionListener {
                override fun processNotStarted(executorId: String, environment: ExecutionEnvironment) {
                    launchEnded(environment)
                }

                override fun processTerminated(
                    executorId: String,
                    environment: ExecutionEnvironment,
                    handler: ProcessHandler,
                    exitCode: Int
                ) {
                    launchEnded(environment)
                }

                /** The topic carries every launch in the project, so ignore everyone else's. */
                private fun launchEnded(environment: ExecutionEnvironment) {
                    if (environment !== env) {
                        return
                    }

                    restoreEnvironment(originalEnv)
                    connection.disconnect()
                }
            }
        )
    }

    private fun preprocessedTasks(): String = preprocessSbtTasks(tasks, useSbtShell)

    private fun restoreEnvironment(originalEnv: Map<String, String>) {
        environmentVariables().clear()
        environmentVariables().putAll(originalEnv)
    }

    private fun maybeWarnAboutPlayProject() {
        if (isLikelyPlayProject()) {
            logWarningToUser("If this is a Play project, disable `Use SBT Shell` for this run configuration.")
        }
    }

    private fun isLikelyPlayProject(): Boolean {
        val basePath = project.basePath ?: return false
        return containsPlayMarker(Path.of(basePath, "build.sbt")) || containsPlayMarker(Path.of(basePath, "project", "plugins.sbt"))
    }

    private fun containsPlayMarker(path: Path): Boolean {
        if (!Files.isRegularFile(path)) {
            return false
        }

        return try {
            val content = Files.readString(path)
            content.contains("PlayScala") || content.contains("PlayJava") || content.contains("org.playframework")
        } catch (_: Exception) {
            false
        }
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

fun preprocessSbtTasks(rawTasks: String, useSbtShell: Boolean): String {
    if (!useSbtShell || rawTasks.trim().startsWith(MIRRORD_SBT_COMMAND_SEPARATOR)) {
        return rawTasks
    }

    val commands = ParametersListUtil.parse(rawTasks, false)
    return if (commands.size == 1) {
        commands.single()
    } else {
        commands.joinToString(
            separator = " $MIRRORD_SBT_COMMAND_SEPARATOR",
            prefix = MIRRORD_SBT_COMMAND_SEPARATOR
        )
    }
}

/**
 * Reads and writes `SbtRunConfiguration.commands`, the stored command string that Scala 2026.2 and
 * later build an SBT shell launch from.
 */
private class SbtStoredCommands private constructor(
    private val configuration: SbtRunConfiguration,
    private val getter: Method,
    private val setter: Method
) {
    var value: String
        get() = getter.invoke(configuration) as String
        set(newValue) {
            setter.invoke(configuration, newValue)
        }

    companion object {
        /** Null on Scala 2026.1 and older, which is the signal to leave the `preprocessTasks` path alone. */
        fun of(configuration: SbtRunConfiguration): SbtStoredCommands? = try {
            SbtStoredCommands(
                configuration,
                SbtRunConfiguration::class.java.getMethod("getCommands"),
                SbtRunConfiguration::class.java.getMethod("setCommands", String::class.java)
            )
        } catch (_: NoSuchMethodException) {
            null
        }
    }
}

/**
 * The SBT shell command that runs [originalCommands] with mirrord's environment applied, or null when
 * the task is not a shape mirrord can wrap.
 */
fun buildMirrordSbtShellCommands(
    originalCommands: String,
    configurationEnv: Map<String, String>,
    mirrordEnv: Map<String, String>,
    envToUnset: Set<String>
): String? {
    val taskScope = resolveSbtTaskScope(originalCommands) ?: return null

    val targetEnv = configurationEnv +
        mirrordEnv +
        mapOf("MIRRORD_DETECT_DEBUGGER_PORT" to "javaagent") -
        envToUnset

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
 * Only simple single-command app launches are supported. Chained commands and watcher commands are
 * left untouched.
 */
private fun resolveSbtTaskScope(commands: String): String? {
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

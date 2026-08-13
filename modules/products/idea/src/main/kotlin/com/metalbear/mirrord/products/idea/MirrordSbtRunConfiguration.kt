package com.metalbear.mirrord.products.idea

import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.components.service
import com.metalbear.mirrord.MirrordExecution
import com.metalbear.mirrord.MirrordLogger
import com.metalbear.mirrord.MirrordLogsService
import com.metalbear.mirrord.MirrordProjectService
import com.metalbear.mirrord.bifrost.MirrordEnvironments
import com.metalbear.mirrord.bifrost.MirrordLaunchContext
import org.jetbrains.sbt.runner.SbtCommandLineState
import org.jetbrains.sbt.runner.SbtRunConfiguration
import scala.Function1
import scala.Option
import scala.runtime.BoxedUnit
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
            pendingMirrordExecution = executionInfo
            super.getState(executor, env) as SbtCommandLineState
        } else {
            createDirectLaunchState(executionInfo, env)
        }
    }

    /**
     * The Scala plugin calls `preprocessTasks` before submitting shell commands.
     * We use that moment to replace a supported app task with a mirrord-aware chained shell command.
     */
    override fun preprocessTasks(): String {
        if (!useSbtShell) {
            return super.preprocessTasks()
        }

        val processedCommands = buildMirrordAwareCommands()
        MirrordLogger.logger.debug(
            "[${this.javaClass.name}] preprocessTasks: configuration=`$name`, commands=`$processedCommands`"
        )
        return processedCommands
    }

    private fun buildMirrordAwareCommands(): String {
        val originalCommands = super.preprocessTasks()
        val executionInfo = pendingMirrordExecution.also { pendingMirrordExecution = null } ?: return originalCommands

        val taskScope = resolveTaskScope(originalCommands) ?: run {
            MirrordLogger.logger.warn(
                "[${this.javaClass.name}] buildMirrordAwareCommands: unsupported SBT task `$originalCommands` for `$name`, running without shell env injection"
            )
            logWarningToUser(
                "mirrord SBT currently supports only simple `run`, `runMain`, and `fgRun` shell tasks. " +
                    "Task `$originalCommands` will run without mirrord shell env injection."
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
     * Prepares mirrord before delegating to either SBT shell execution or direct SBT launch.
     *
     * This is intentionally eager because SBT execution does not reliably pass through the generic
     * Java parameter extension hook used by regular JVM run configurations.
     */
    private fun prepareMirrordExecution(): MirrordExecution? {
        if (useSbtShell) {
            maybeWarnAboutPlayProject()

            val originalCommands = super.preprocessTasks()
            val taskScope = resolveTaskScope(originalCommands) ?: run {
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
        val environment = MirrordEnvironments.resolve(MirrordLaunchContext(project))
        return service.execManager.wrapper("idea", getSbtConfigurationEnv(this), environment).start()
    }

    private fun createDirectLaunchState(
        executionInfo: MirrordExecution,
        env: ExecutionEnvironment
    ): SbtCommandLineState {
        val originalEnv = HashMap(environmentVariables())
        val injectedEnv = originalEnv +
            executionInfo.environment +
            mapOf("MIRRORD_DETECT_DEBUGGER_PORT" to "javaagent") -
            executionInfo.envToUnset.orEmpty().toSet()

        environmentVariables().clear()
        environmentVariables().putAll(injectedEnv)

        return object : SbtCommandLineState(
            preprocessTasks(),
            this@MirrordSbtRunConfiguration,
            env,
            Option.empty<Function1<String, BoxedUnit>>()
        ) {
            override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
                return try {
                    super.execute(executor, runner).also { result ->
                        val processHandler = result.processHandler
                        if (processHandler == null) {
                            restoreEnvironment(originalEnv)
                        } else {
                            processHandler.addProcessListener(object : ProcessAdapter() {
                                override fun processTerminated(event: ProcessEvent) {
                                    restoreEnvironment(originalEnv)
                                }
                            })
                        }
                    }
                } catch (t: Throwable) {
                    restoreEnvironment(originalEnv)
                    throw t
                }
            }
        }
    }

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

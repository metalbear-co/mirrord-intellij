@file:Suppress("DialogTitleCapitalization")

package com.metalbear.mirrord

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonSyntaxException
import com.google.gson.annotations.SerializedName
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.wsl.WSLCommandLineOptions
import com.intellij.execution.wsl.WSLDistribution
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import java.util.concurrent.*

const val GITHUB_URL = "https://github.com/metalbear-co/mirrord"

/**
 * The message types we get from mirrord-cli.
 *
 * See `mirrord/progress/src/lib.rs` `ProgressMessage`.
 */
enum class MessageType {
    NewTask, FinishedTask, Warning, Info, IdeMessage
}

// I don't know how to do tags like Rust so this format is for parsing both kind of messages ;_;
data class Message(val type: MessageType, val name: String, val parent: String?, val success: Boolean?, val message: Any?)

/**
 * How the `IdeMessage` should be displayed (the level of the notification box).
 */
enum class NotificationLevel {
    Info, Warning
}

/**
 * Rust enum equivalent to the `IdeAction`.
 *
 * Converted from a `JsonObject` from `IdeMessage`.
 */
sealed class IdeAction {
    /**
     * A link action that appears in the notification, such as "Get help".
     *
     * @param label The text of the link: "Get help".
     * @param link The Url.
     */
    data class Link(val label: String, val link: String) : IdeAction()
}

/**
 * Message we get from mirrord in json format, when `MessageType` is `IdeMessage`.
 *
 * Holds not only the content text that is displayed in a notification box, but also actions/buttons.
 *
 * These types of messages are shown as notifications by `IdeMessage::handleMessage`.
 *
 * @param id Identifier for the message, so we can trigger "Don't show this again".
 * @param level Type of notification box such as `info`, `warning`.
 * @param text Main content of the notification.
 * @param actions The actions/buttons that are shown in the notification box.
 */
data class IdeMessage(val id: String, val level: NotificationLevel, val text: String, val actions: Set<JsonObject>) {

    /**
     * Handles the `IdeMessage` that we received from mirrord.
     *
     * @param service Used to build the notification.
     */
    fun handleIdeMessage(service: MirrordProjectService) {
        val notification = when (level) {
            NotificationLevel.Info -> service.notifier.notification(text, NotificationType.INFORMATION)
            NotificationLevel.Warning -> service.notifier.notification(text, NotificationType.WARNING)
        }

        this.actions.forEach {
            if (it["kind"].asString == "Link") {
                val action = Gson().fromJson(it, IdeAction.Link::class.java)
                var link = action.link.replace("utm_medium=plugin", "utm_medium=intellij")
                link = link.replace("utm_medium=cli", "utm_medium=intellij")
                notification.withLink(action.label, link)
            }
        }

        notification.fire()
    }
}

data class Error(val message: String, val severity: String, val causes: List<String>, val help: String, val labels: List<String>, val related: List<String>)

data class MirrordExecution(
    val environment: MutableMap<String, String>,
    @SerializedName("patched_path") val patchedPath: String?,
    @SerializedName("env_to_unset") val envToUnset: List<String>?,
    @SerializedName("uses_operator") val usesOperator: Boolean?
)

data class MirrordContainerExecution(
    val runtime: String,
    @SerializedName("extra_args") val extraArgs: MutableList<String>,
    @SerializedName("uses_operator") val usesOperator: Boolean?
)

/**
 * Wrapper around Gson for parsing messages from the mirrord binary.
 */
private class SafeParser {
    private val gson = Gson()

    /**
     * @throws MirrordError
     */
    fun <T> parse(value: String, classOfT: Class<T>): T {
        return try {
            gson.fromJson(value, classOfT)
        } catch (e: Throwable) {
            MirrordLogger.logger.debug("failed to parse mirrord binary message $value", e)
            throw MirrordError("failed to parse a message from the mirrord binary, try updating to the latest version", e)
        }
    }
}

/**
 * How many times mirrord can be run before asking for marketplace review.
 */
private const val FEEDBACK_COUNTER_REVIEW_AFTER = 100

/**
 * How many times mirrord can be run before inviting the user to Discord.
 */
private const val DISCORD_COUNTER_INVITE_AFTER = 10

/**
 * How many times mirrord can be run before inviting the user to mirrord for Teams **for the first time**.
 */
private const val MIRRORD_FOR_TEAMS_INVITE_AFTER = 100

/**
 * How many times mirrord can run before inviting the user to mirrord for Teams **again**.
 */
private const val MIRRORD_FOR_TEAMS_INVITE_EVERY = 30

/**
 * Name of the environment variable used to trigger rich output of `mirrord ls`.
 */
private const val MIRRORD_LS_RICH_OUTPUT_ENV = "MIRRORD_LS_RICH_OUTPUT"

/**
 * Name of the environment variable used to specify which resource types to list with `mirrord ls`
 */
private const val MIRRORD_LS_TARGET_TYPES_ENV = "MIRRORD_LS_TARGET_TYPES"

/**
 * Interact with mirrord CLI using this API.
 */
class MirrordApi(private val service: MirrordProjectService, private val projectEnvVars: Map<String, String>?) {
    /**
     * New format of found target returned from `mirrord ls`.
     */
    data class FoundTarget(
        /**
         * Path to the target, e.g `pod/my-pod`.
         */
        val path: String,
        /**
         * Whether this target can be selected.
         */
        val available: Boolean
    )

    /**
     * New format of `mirrord ls`, enabled by setting MIRRORD_LS_RICH_OUTPUT_ENV to `true`.
     */
    private data class RichOutput(
        /**
         * Targets found in the namespace.
         */
        val targets: Array<FoundTarget>,
        /**
         * Namespace where the lookup was done.
         */
        @SerializedName("current_namespace") val currentNamespace: String,
        /**
         * All namespaces available to the user.
         */
        val namespaces: Array<String>
    ) {
        /**
         * Generated by IntelliJ.
         *
         * If it's not overrode, we get a warning, because this class has an Array field.
         */
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as RichOutput

            if (!targets.contentEquals(other.targets)) return false
            if (currentNamespace != other.currentNamespace) return false
            if (!namespaces.contentEquals(other.namespaces)) return false

            return true
        }

        /**
         * Generated by IntelliJ.
         *
         * If it's not overrode, we get a warning, because this class has an Array field.
         */
        override fun hashCode(): Int {
            var result = targets.contentHashCode()
            result = 31 * result + currentNamespace.hashCode()
            result = 31 * result + namespaces.contentHashCode()
            return result
        }
    }

    /**
     * Output of `mirrord ls`.
     */
    class MirrordLsOutput(
        /**
         * List of found targets.
         */
        val targets: List<FoundTarget>,
        /**
         * Namespace where the lookup was done.
         */
        val currentNamespace: String?,
        /**
         * All namespaces available to the user.
         */
        val namespaces: List<String>?
    )

    private class MirrordLsTask(cli: String, projectEnvVars: Map<String, String>?) : MirrordCliTask<MirrordLsOutput>(cli, "ls", null, projectEnvVars) {
        override fun compute(project: Project, process: Process, setText: (String) -> Unit): MirrordLsOutput {
            setText("mirrord is listing targets...")

            process.waitFor()
            if (process.exitValue() != 0) {
                val processStdError = process.errorStream.bufferedReader().readText()
                throw MirrordError.fromStdErr(processStdError)
            }

            val data = process.inputStream.bufferedReader().readText()
            MirrordLogger.logger.debug("parsing mirrord ls output: $data")

            val output = try {
                val richOutput = SafeParser().parse(data, RichOutput::class.java)
                MirrordLsOutput(richOutput.targets.toList(), richOutput.currentNamespace, richOutput.namespaces.toList())
            } catch (error: Throwable) {
                if (error.cause != null && error.cause is JsonSyntaxException) {
                    val simpleOutput = SafeParser().parse(data, Array<String>::class.java)
                    MirrordLsOutput(
                        simpleOutput.map { FoundTarget(it, true) },
                        null,
                        null
                    )
                } else {
                    throw error
                }
            }

            if (output.targets.isEmpty()) {
                project
                    .service<MirrordProjectService>()
                    .notifier
                    .notifySimple(
                        "No mirrord target available in the configured namespace. " +
                            "You can run targetless, or set a different target namespace " +
                            "or kubeconfig in the mirrord configuration file.",
                        NotificationType.INFORMATION
                    )
            }

            return output
        }
    }

    /**
     * Runs `mirrord ls`, optionally with ` -t <targetType>`, to get the list of available targets.
     * Displays a modal progress dialog.
     *
     * @return available targets
     */
    fun listTargets(cli: String, configFile: String?, wslDistribution: WSLDistribution?, namespace: String?, targetTypes: List<String>): MirrordLsOutput {
        val envVars: MutableMap<String, String> = projectEnvVars.orEmpty().toMutableMap()
        envVars[MIRRORD_LS_RICH_OUTPUT_ENV] = "true"
        targetTypes.takeIf { it.isNotEmpty() }?.let {
            Gson().toJson(it)
        }?.takeIf { it.isNotEmpty() }?.also { targetTypesJson: String ->
            envVars[MIRRORD_LS_TARGET_TYPES_ENV] = targetTypesJson
        }

        val task = MirrordLsTask(cli, envVars.toMap()).apply {
            this.namespace = namespace
            this.configFile = configFile
            this.wslDistribution = wslDistribution
            this.output = "json"
        }

        return task.run(service.project)
    }

    private class MirrordExtTask(cli: String, projectEnvVars: Map<String, String>?) : MirrordCliTask<MirrordExecution>(cli, "ext", null, projectEnvVars) {
        override fun compute(project: Project, process: Process, setText: (String) -> Unit): MirrordExecution {
            val parser = SafeParser()
            val bufferedReader = process.inputStream.reader().buffered()

            val warningHandler = MirrordWarningHandler(project.service<MirrordProjectService>())

            setText("mirrord is starting...")
            for (line in bufferedReader.lines()) {
                val message = parser.parse(line, Message::class.java)
                when {
                    message.name == "mirrord preparing to launch" && message.type == MessageType.FinishedTask -> {
                        val success = message.success
                            ?: throw MirrordError("invalid message received from the mirrord binary")
                        if (success) {
                            val innerMessage = message.message
                                ?: throw MirrordError("invalid message received from the mirrord binary")
                            val executionInfo = parser.parse(innerMessage as String, MirrordExecution::class.java)
                            setText("mirrord is running")
                            return executionInfo
                        }
                    }

                    message.type == MessageType.Info -> {
                        val service = project.service<MirrordProjectService>()
                        message.message?.let { service.notifier.notifySimple(it as String, NotificationType.INFORMATION) }
                    }

                    message.type == MessageType.Warning -> {
                        message.message?.let { warningHandler.handle(it as String) }
                    }

                    message.type == MessageType.IdeMessage -> {
                        message.message?.run {
                            val ideMessage = Gson().fromJson(Gson().toJsonTree(this), IdeMessage::class.java)
                            val service = project.service<MirrordProjectService>()
                            ideMessage?.handleIdeMessage(service)
                        }
                    }

                    else -> {
                        var displayMessage = message.name
                        message.message?.let {
                            displayMessage += ": $it"
                        }
                        setText(displayMessage)
                    }
                }
            }

            process.waitFor()
            if (process.exitValue() != 0) {
                val processStdError = process.errorStream.bufferedReader().readText()
                throw MirrordError.fromStdErr(processStdError)
            } else {
                throw MirrordError("invalid output of the mirrord binary")
            }
        }
    }

    private class MirrordContainerExtTask(cli: String, projectEnvVars: Map<String, String>?) : MirrordCliTask<MirrordContainerExecution>(cli, "container-ext", null, projectEnvVars) {
        override fun compute(project: Project, process: Process, setText: (String) -> Unit): MirrordContainerExecution {
            val parser = SafeParser()
            val bufferedReader = process.inputStream.reader().buffered()

            val warningHandler = MirrordWarningHandler(project.service<MirrordProjectService>())

            setText("mirrord is starting...")
            for (line in bufferedReader.lines()) {
                val message = parser.parse(line, Message::class.java)
                when {
                    message.name == "mirrord preparing to launch" && message.type == MessageType.FinishedTask -> {
                        val success = message.success
                            ?: throw MirrordError("invalid message received from the mirrord binary")
                        if (success) {
                            val innerMessage = message.message
                                ?: throw MirrordError("invalid message received from the mirrord binary")
                            val executionInfo = parser.parse(innerMessage as String, MirrordContainerExecution::class.java)
                            setText("mirrord is running")
                            return executionInfo
                        }
                    }

                    message.type == MessageType.Info -> {
                        val service = project.service<MirrordProjectService>()
                        message.message?.let { service.notifier.notifySimple(it as String, NotificationType.INFORMATION) }
                    }

                    message.type == MessageType.Warning -> {
                        message.message?.let { warningHandler.handle(it as String) }
                    }

                    message.type == MessageType.IdeMessage -> {
                        message.message?.run {
                            val ideMessage = Gson().fromJson(Gson().toJsonTree(this), IdeMessage::class.java)
                            val service = project.service<MirrordProjectService>()
                            ideMessage?.handleIdeMessage(service)
                        }
                    }

                    else -> {
                        var displayMessage = message.name
                        message.message?.let {
                            displayMessage += ": $it"
                        }
                        setText(displayMessage)
                    }
                }
            }

            process.waitFor()
            if (process.exitValue() != 0) {
                val processStdError = process.errorStream.bufferedReader().readText()
                throw MirrordError.fromStdErr(processStdError)
            } else {
                throw MirrordError("invalid output of the mirrord binary")
            }
        }
    }

    /**
     * Interacts with the `mirrord verify-config [path]` cli command.
     *
     * Reads the output (json) from stdout which contain either a success + warnings, or the errors from the verify
     * command.
     */
    private class MirrordVerifyConfigTask(cli: String, path: String, projectEnvVars: Map<String, String>?) : MirrordCliTask<String>(cli, "verify-config", listOf("--ide", path), projectEnvVars) {
        override fun compute(project: Project, process: Process, setText: (String) -> Unit): String {
            setText("mirrord is verifying the config options...")
            process.waitFor()
            if (process.exitValue() != 0) {
                val processStdError = process.errorStream.bufferedReader().readText()
                throw MirrordError.fromStdErr(processStdError)
            }

            val bufferedReader = process.inputStream.reader().buffered()
            val stderr = process.errorStream.reader().buffered()
            MirrordLogger.logger.debug(stderr.readText())

            return bufferedReader.readText()
        }
    }

    /**
     * Executes the `mirrord verify-config [path]` task.
     *
     * @return String containing a json with either a success + warnings, or the verified config errors.
     */
    fun verifyConfig(
        cli: String,
        configFilePath: String,
        wslDistribution: WSLDistribution?
    ): String {
        val verifyConfigTask = MirrordVerifyConfigTask(cli, configFilePath, projectEnvVars).apply {
            this.wslDistribution = wslDistribution
        }
        return verifyConfigTask.run(service.project)
    }

    /**
     * Runs `mirrord ext` command to get the environment.
     * Displays a modal progress dialog.
     *
     * @return environment for the user's application
     */
    fun exec(cli: String, target: MirrordExecDialog.UserSelection, configFile: String?, executable: String?, wslDistribution: WSLDistribution?): MirrordExecution {
        bumpRunCounter()

        val task = MirrordExtTask(cli, projectEnvVars).apply {
            this.target = target.target
            this.namespace = target.namespace
            this.configFile = configFile
            this.executable = executable
            this.wslDistribution = wslDistribution
        }

        val result = task.run(service.project)
        service.notifier.notifySimple("mirrord starting...", NotificationType.INFORMATION)

        result.usesOperator?.let { usesOperator ->
            if (usesOperator) {
                MirrordSettingsState.instance.mirrordState.operatorUsed = true
            }
        }

        return result
    }

    fun containerExec(cli: String, target: MirrordExecDialog.UserSelection, configFile: String?, wslDistribution: WSLDistribution?): MirrordContainerExecution {
        bumpRunCounter()

        val task = MirrordContainerExtTask(cli, projectEnvVars).apply {
            this.target = target.target
            this.namespace = target.namespace
            this.configFile = configFile
            this.wslDistribution = wslDistribution
        }

        val result = task.run(service.project)
        service.notifier.notifySimple("mirrord starting...", NotificationType.INFORMATION)

        result.usesOperator?.let { usesOperator ->
            if (usesOperator) {
                MirrordSettingsState.instance.mirrordState.operatorUsed = true
            }
        }

        return result
    }

    /**
     * Increments the mirrord run counter.
     * Can display some notifications (asking for feedback, discord invite, mirrord for Teams invite).
     */
    private fun bumpRunCounter() {
        val previousRuns = MirrordSettingsState.instance.mirrordState.runsCounter
        val currentRuns = previousRuns + 1
        MirrordSettingsState.instance.mirrordState.runsCounter = currentRuns

        val operatorUsed = MirrordSettingsState.instance.mirrordState.operatorUsed

        if ((currentRuns % FEEDBACK_COUNTER_REVIEW_AFTER) == 0) {
            service.notifier.notification("Enjoying mirrord? Don't forget to leave a review or star us on GitHub!", NotificationType.INFORMATION).withLink("Review", "https://plugins.jetbrains.com/plugin/19772-mirrord/reviews").withLink("Star us on GitHub", GITHUB_URL).withDontShowAgain(MirrordSettingsState.NotificationId.PLUGIN_REVIEW).fire()
        }

        if (currentRuns == DISCORD_COUNTER_INVITE_AFTER) {
            service.notifier.notification("Need any help with mirrord? Come chat with our team on Discord!", NotificationType.INFORMATION).withLink("Join us", "https://discord.gg/metalbear").withDontShowAgain(MirrordSettingsState.NotificationId.DISCORD_INVITE).fire()
        }

        if (previousRuns >= MIRRORD_FOR_TEAMS_INVITE_AFTER && !operatorUsed) {
            if ((previousRuns - MIRRORD_FOR_TEAMS_INVITE_AFTER) % MIRRORD_FOR_TEAMS_INVITE_EVERY == 0) {
                service.notifier.notification("For more features of mirrord, including multi-pod impersonation, check out mirrord for Teams!", NotificationType.INFORMATION).withLink("Try it now", MIRRORD_FOR_TEAMS_URL).withDontShowAgain(MirrordSettingsState.NotificationId.MIRRORD_FOR_TEAMS).fire()
            }
        }
    }
}

/**
 * A mirrord CLI invocation.
 *
 * @param args: An extra list of arguments (used by `verify-config`).
 */
private abstract class MirrordCliTask<T>(private val cli: String, private val command: String, private val args: List<String>?, private val projectEnvVars: Map<String, String>?) {
    var target: String? = null
    var namespace: String? = null
    var configFile: String? = null
    var executable: String? = null
    var wslDistribution: WSLDistribution? = null
    var output: String? = null

    /**
     * Returns command line for execution.
     */
    private fun prepareCommandLine(project: Project): GeneralCommandLine {
        return GeneralCommandLine(cli, command).apply {
            // Merge our `environment` vars with what's set in the current launch run configuration.
            if (projectEnvVars != null) {
                environment.putAll(projectEnvVars)
            }

            target?.let {
                addParameter("-t")
                addParameter(it)
            }

            namespace?.let {
                environment.put("MIRRORD_TARGET_NAMESPACE", it)
            }

            configFile?.let {
                val formattedPath = wslDistribution?.getWslPath(it) ?: it
                addParameter("-f")
                addParameter(formattedPath)
            }

            executable?.let {
                addParameter("-e")
                addParameter(it)
            }

            wslDistribution?.let {
                val wslOptions = WSLCommandLineOptions().apply {
                    isLaunchWithWslExe = true
                    isExecuteCommandInShell = false
                }
                it.patchCommandLine(this, project, wslOptions)
            }

            output?.let {
                addParameter("-o")
                addParameter(it)
            }

            args?.let { extraArgs -> extraArgs.forEach { addParameter(it) } }

            environment["MIRRORD_PROGRESS_MODE"] = "json"
            environment["MIRRORD_PROGRESS_SUPPORT_IDE"] = "true"
        }
    }

    /**
     * Processes the output of the mirrord process. If the user cancels the computation, process is destroyed.
     * @param setText used to present info about the computation state to the user
     */
    protected abstract fun compute(project: Project, process: Process, setText: (String) -> Unit): T

    /**
     * Computes the result of this invocation in a background thread. Periodically checks if the user has canceled.
     * The extra background thread is here to make the `Cancel` button responsive
     * (inner computation blocks on reading mirrord process output).
     *
     * @throws ProcessCanceledException if the user has canceled
     */
    private fun computeWithResponsiveCancel(project: Project, process: Process, progress: ProgressChecker): T {
        val result = CompletableFuture<T>()

        // There is a version of this method that takes a `Callable<T>`, but its implementation is broken.
        // Therefore, we use this one and `CompletableFuture<T>`.
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val computationResult = compute(project, process) { text -> progress.setProgressMessage(text) }
                result.complete(computationResult)
            } catch (e: Throwable) {
                if (!progress.isCanceled()) {
                    result.completeExceptionally(e)
                }
            }
        }

        while (true) {
            progress.checkCanceled()

            try {
                return result.get(200, TimeUnit.MILLISECONDS)
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            } catch (e: CancellationException) {
                throw ProcessCanceledException(e)
            } catch (_: TimeoutException) {
            }
        }
    }

    /**
     * Computes the result of this invocation with a progress UI:
     * * If called from the event dispatch thread, displays a modal dialog
     * * If called from a background thread, displays a progress indicator
     * The user can cancel the computation.
     *
     * @throws ProcessCanceledException if the user has canceled
     */
    fun run(project: Project): T {
        val commandLine = prepareCommandLine(project)
        MirrordLogger.logger.info("running mirrord task with following command line: ${commandLine.commandLineString}")

        val process = commandLine.toProcessBuilder().redirectOutput(ProcessBuilder.Redirect.PIPE).redirectError(ProcessBuilder.Redirect.PIPE).start()

        return if (ApplicationManager.getApplication().isDispatchThread) {
            // Modal dialog with progress is very visible and can be canceled by the user,
            // so we don't use any timeout here.
            ProgressManager.getInstance().run(object : Task.WithResult<T, Exception>(project, "mirrord", true) {
                override fun compute(indicator: ProgressIndicator): T {
                    return computeWithResponsiveCancel(project, process, IndicatorProgressChecker(indicator))
                }

                override fun onCancel() {
                    MirrordLogger.logger.info("mirrord task `${commandLine.commandLineString}` was cancelled")
                    process.destroy()
                }

                override fun onThrowable(error: Throwable) {
                    MirrordLogger.logger.error("mirrord task `${commandLine.commandLineString}` failed", error)
                    process.destroy()
                }
            })
        } else if (!ApplicationManager.getApplication().isReadAccessAllowed) {
            val env = CompletableFuture<T>()

            ProgressManager.getInstance().run(object : Task.Backgroundable(project, "mirrord", true) {
                override fun run(indicator: ProgressIndicator) {
                    val res = computeWithResponsiveCancel(project, process, IndicatorProgressChecker(indicator))
                    env.complete(res)
                }

                override fun onCancel() {
                    MirrordLogger.logger.info("mirrord task `${commandLine.commandLineString}` was cancelled")
                    process.destroy()
                    env.cancel(true)
                }

                override fun onThrowable(error: Throwable) {
                    MirrordLogger.logger.error("mirrord task `${commandLine.commandLineString}` failed", error)
                    process.destroy()
                    env.completeExceptionally(error)
                }
            })

            try {
                env.get(2, TimeUnit.MINUTES)
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            } catch (e: CancellationException) {
                throw ProcessCanceledException(e)
            } catch (e: TimeoutException) {
                process.destroy()
                MirrordLogger.logger.error("mirrord task `${commandLine.commandLineString} timed out", e)
                throw MirrordError("mirrord process timed out")
            }
        } else {
            // Not on the EDT thread and under a read lock.
            // Cannot spawn a background task here, because it schedules start on the EDT thread
            // (to update the UI with a progress indicator).
            // EDT thread requires a write lock, so using a background task here would cause a deadlock.
            try {
                computeWithResponsiveCancel(project, process, TimeoutProgressChecker(2, TimeUnit.MINUTES))
            } catch (e: ProcessCanceledException) {
                // In this case, process is canceled only after a timeout.
                process.destroy()
                MirrordLogger.logger.error("mirrord task `${commandLine.commandLineString} timed out", e)
                throw MirrordError("mirrord process timed out")
            }
        }
    }
}

/**
 * A handle for tasks that are spawned raw on a pooled thread.
 */
private interface ProgressChecker {
    /**
     * Whether this task has been canceled.
     */
    fun isCanceled(): Boolean

    /**
     * Sets a message about the current stage of the task.
     * Most of the time this is displayed to the user.
     */
    fun setProgressMessage(text: String) {}

    /**
     * @throws ProcessCanceledException if this task has been canceled.
     */
    fun checkCanceled() {
        if (isCanceled()) {
            throw ProcessCanceledException()
        }
    }
}

/**
 * Ignores progress messages and cancels the task after the given timeout.
 */
private class TimeoutProgressChecker(timeout: Long, timeUnit: TimeUnit) : ProgressChecker {
    private val startedAt: Long = System.nanoTime()
    private val limit: Long = timeUnit.toNanos(timeout)

    override fun isCanceled(): Boolean {
        val elapsed = System.nanoTime() - startedAt
        return elapsed >= limit
    }
}

/**
 * Wraps a `ProgressIndicator`.
 *
 * @see ProgressIndicator
 */
private class IndicatorProgressChecker(private val indicator: ProgressIndicator) : ProgressChecker {
    override fun isCanceled(): Boolean {
        return indicator.isCanceled
    }

    override fun checkCanceled() {
        indicator.checkCanceled()
    }

    override fun setProgressMessage(text: String) {
        indicator.text = text
    }
}

private class MirrordWarningHandler(private val service: MirrordProjectService) {
    /**
     * Matches warning message from the mirrord binary to the notification id.
     */
    private class WarningFilter(private val filter: (message: String) -> Boolean, private val id: MirrordSettingsState.NotificationId) {
        fun getId(warningMessage: String): MirrordSettingsState.NotificationId? {
            return if (filter(warningMessage)) {
                id
            } else {
                null
            }
        }
    }

    private val filters: List<WarningFilter> = listOf(WarningFilter({ message -> message.contains("Agent version") && message.contains("does not match the local mirrord version") }, MirrordSettingsState.NotificationId.AGENT_VERSION_MISMATCH))

    /**
     * Shows the warning notification, optionally providing the "Don't show again" option.
     */
    fun handle(warningMessage: String) {
        val notification = service.notifier.notification(warningMessage, NotificationType.WARNING)

        filters.firstNotNullOfOrNull { it.getId(warningMessage) }?.let {
            notification.withDontShowAgain(it)
        }

        notification.fire()
    }
}

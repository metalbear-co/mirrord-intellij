@file:Suppress("DialogTitleCapitalization")

package com.metalbear.mirrord

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonSyntaxException
import com.google.gson.annotations.SerializedName
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.metalbear.mirrord.bifrost.MirrordEnvironment
import com.metalbear.mirrord.bifrost.MirrordProcessSpec
import com.metalbear.mirrord.bifrost.TargetPath
import java.util.concurrent.*

const val GITHUB_URL = "https://github.com/metalbear-co/mirrord"

const val NEWSLETTER_SIGNUP_URL = "https://metalbear.com/newsletter" + "?utm_medium=intellij&utm_source=newsletter"

const val MIRRORD_LISTING_TARGETS_MESSAGE = "mirrord is listing targets..."
const val MIRRORD_STARTING_MESSAGE = "mirrord is starting..."
const val MIRRORD_RUNNING_MESSAGE = "mirrord is running"
const val MIRRORD_CONTAINER_STARTING_MESSAGE = "mirrord container execution starting..."
const val MIRRORD_CONTAINER_RUNNING_MESSAGE = "mirrord container is running"
const val MIRRORD_VERIFYING_CONFIG_MESSAGE = "mirrord is verifying the config options..."
const val MIRRORD_VERIFIED_CONFIG_MESSAGE = "Config verification completed successfully"

// Error message constants
private fun getTargetListingFailedError(processStdError: String) = "Target listing failed: $processStdError"
private fun getProcessFailedStderrError(processStdError: String) = "Process failed with stderr: $processStdError"
private fun getContainerProcessFailedStderrError(processStdError: String) = "Container process failed with stderr: $processStdError"
private fun getConfigVerificationFailedError(processStdError: String) = "Config verification failed: $processStdError"

/**
 * The project's current branch, for Jira integration metrics.
 *
 * Stays on the IDE host on purpose: this reads the project's own VCS, which the IDE already
 * has open locally, and a failure here must never stop a run.
 */
private fun gitBranchOf(project: Project): String? {
    val dir = project.guessProjectDir()?.canonicalPath ?: return null
    return try {
        val process = Runtime.getRuntime().exec(arrayOf("git", "-C", dir, "branch", "--show-current"))
        if (process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0) {
            process.inputStream.bufferedReader().use { it.readText() }.trim().takeIf { it.isNotEmpty() }
        } else {
            MirrordLogger.logger.debug("error retrieving git branch: ${process.errorStream.bufferedReader().use { it.readText() }}")
            null
        }
    } catch (e: Exception) {
        MirrordLogger.logger.debug("exception while running git command, Jira integration metrics will not be recorded: $e")
        null
    }
}

private fun getMirrordTaskFailedError(commandLine: String, error: Throwable) =
    "mirrord task failed for $commandLine: ${error.message ?: error.toString()}"
private fun getMirrordBackgroundTaskFailedError(commandLine: String, error: Throwable) =
    "mirrord background task failed for $commandLine: ${error.message ?: error.toString()}"
private fun getMirrordTaskTimedOutError(commandLine: String) = "mirrord task timed out: $commandLine"
private fun getMirrordTaskTimedOutUnderReadLockError(commandLine: String) = "mirrord task timed out under read lock: $commandLine"
private fun getMirrordTaskCancelledMessage(commandLine: String) = "mirrord task was cancelled: $commandLine"
private fun getMirrordBackgroundTaskCancelledMessage(commandLine: String) = "mirrord background task was cancelled: $commandLine"

/**
 * Helper function to log errors to both MirrordLogger and logsService
 */
private fun logErrorToBoth(logsService: MirrordLogsService, errorMessage: String) {
    MirrordLogger.logger.error(errorMessage)
    logsService.logError(errorMessage)
}

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

data class MirrordAttachExecution(
    val environment: MutableMap<String, String>,
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
 * How many times mirrord can be run before inviting the user to Slack.
 */
private const val SLACK_COUNTER_INVITE_AFTER = 10

/**
 * How many times mirrord can be run before inviting the user to mirrord for Teams **for the first time**.
 */
private const val MIRRORD_FOR_TEAMS_INVITE_AFTER = 100

/**
 * How many times mirrord can run before inviting the user to mirrord for Teams **again**.
 */
private const val MIRRORD_FOR_TEAMS_INVITE_EVERY = 30

/**
 * How many times mirrord can be run before inviting the user to sign up to the newsletter with corresponding message.
 */
private const val NEWSLETTER_COUNTER_PROMPT_AFTER_FIRST = 20
private const val NEWSLETTER_COUNTER_PROMPT_AFTER_SECOND = 100

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

    private class MirrordLsTask(cli: TargetPath, projectEnvVars: Map<String, String>?, environment: MirrordEnvironment) : MirrordCliTask<MirrordLsOutput>(cli, "ls", null, projectEnvVars, environment) {
        override fun compute(project: Project, process: Process, setText: (String) -> Unit): MirrordLsOutput {
            val logsService = project.service<MirrordLogsService>()

            setText(MIRRORD_LISTING_TARGETS_MESSAGE)
            logsService.logInfo(MIRRORD_LISTING_TARGETS_MESSAGE)

            process.waitFor()
            if (process.exitValue() != 0) {
                failFromExit(logsService, process, message = ::getTargetListingFailedError)
            }

            val data = process.inputStream.bufferedReader().readText()
            MirrordLogger.logger.debug("parsing mirrord ls output: $data")
            logsService.logInfo("Successfully retrieved target list")

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
    fun listTargets(cli: TargetPath, configFile: TargetPath?, environment: MirrordEnvironment, namespace: String?, targetTypes: List<String>): MirrordLsOutput {
        val envVars: MutableMap<String, String> = projectEnvVars.orEmpty().toMutableMap()
        envVars[MIRRORD_LS_RICH_OUTPUT_ENV] = "true"
        targetTypes.takeIf { it.isNotEmpty() }?.let {
            Gson().toJson(it)
        }?.takeIf { it.isNotEmpty() }?.also { targetTypesJson: String ->
            envVars[MIRRORD_LS_TARGET_TYPES_ENV] = targetTypesJson
        }

        val task = MirrordLsTask(cli, envVars.toMap(), environment).apply {
            this.namespace = namespace
            this.configFile = configFile
            this.output = "json"
        }

        return task.run(service.project)
    }

    private class MirrordExtTask(cli: TargetPath, projectEnvVars: Map<String, String>?, environment: MirrordEnvironment) : MirrordCliTask<MirrordExecution>(cli, "ext", null, projectEnvVars, environment) {
        override fun compute(project: Project, process: Process, setText: (String) -> Unit): MirrordExecution {
            val parser = SafeParser()
            val bufferedReader = process.inputStream.reader().buffered()

            val warningHandler = MirrordWarningHandler(project.service<MirrordProjectService>())
            val logsService = project.service<MirrordLogsService>()

            logsService.onMirrordExecutionStart()

            setText(MIRRORD_STARTING_MESSAGE)
            logsService.logInfo(MIRRORD_STARTING_MESSAGE)

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
                            setText(MIRRORD_RUNNING_MESSAGE)
                            logsService.logInfo(MIRRORD_RUNNING_MESSAGE)
                            return executionInfo
                        }
                    }

                    message.type == MessageType.Info -> {
                        message.message?.let {
                            val msg = it as String
                            logsService.logInfo(msg)
                        }
                    }

                    message.type == MessageType.Warning -> {
                        message.message?.let {
                            val msg = it as String
                            warningHandler.handle(msg)
                            logsService.logWarning(msg)
                        }
                    }

                    message.type == MessageType.IdeMessage -> {
                        message.message?.run {
                            logsService.logInfo("IDE Message: $this")
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
                        logsService.logMessage("Task: $displayMessage")
                    }
                }
            }

            process.waitFor()
            if (process.exitValue() != 0) {
                failFromExit(logsService, process, endsExecution = true, message = ::getProcessFailedStderrError)
            } else {
                logsService.logError("Invalid output from mirrord binary")
                logsService.onMirrordExecutionEnd()
                throw MirrordError("invalid output of the mirrord binary")
            }
        }
    }

    private class MirrordContainerExtTask(cli: TargetPath, projectEnvVars: Map<String, String>?, environment: MirrordEnvironment) : MirrordCliTask<MirrordContainerExecution>(cli, "container-ext", null, projectEnvVars, environment) {
        override fun compute(project: Project, process: Process, setText: (String) -> Unit): MirrordContainerExecution {
            val parser = SafeParser()
            val bufferedReader = process.inputStream.reader().buffered()

            val warningHandler = MirrordWarningHandler(project.service<MirrordProjectService>())
            val logsService = project.service<MirrordLogsService>()

            logsService.onMirrordExecutionStart()

            setText(MIRRORD_CONTAINER_STARTING_MESSAGE)
            logsService.logInfo(MIRRORD_CONTAINER_STARTING_MESSAGE)

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
                            setText(MIRRORD_CONTAINER_RUNNING_MESSAGE)
                            logsService.logInfo(MIRRORD_CONTAINER_RUNNING_MESSAGE)
                            return executionInfo
                        }
                    }

                    message.type == MessageType.Info -> {
                        message.message?.let {
                            val msg = it as String
                            logsService.logInfo(msg)
                        }
                    }

                    message.type == MessageType.Warning -> {
                        message.message?.let {
                            val msg = it as String
                            warningHandler.handle(msg)
                            logsService.logWarning(msg)
                        }
                    }

                    message.type == MessageType.IdeMessage -> {
                        message.message?.run {
                            val ideMessage = Gson().fromJson(Gson().toJsonTree(this), IdeMessage::class.java)
                            val service = project.service<MirrordProjectService>()
                            ideMessage?.handleIdeMessage(service)
                            logsService.logInfo("IDE Message: ${ideMessage?.text ?: "Unknown message"}")
                        }
                    }

                    else -> {
                        var displayMessage = message.name
                        message.message?.let {
                            displayMessage += ": $it"
                        }
                        setText(displayMessage)
                        logsService.logMessage("Task: $displayMessage")
                    }
                }
            }

            process.waitFor()
            if (process.exitValue() != 0) {
                failFromExit(logsService, process, endsExecution = true, message = ::getContainerProcessFailedStderrError)
            } else {
                logsService.logError("Invalid output from mirrord container binary")
                logsService.onMirrordExecutionEnd()
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
    private class MirrordVerifyConfigTask(cli: TargetPath, configPath: TargetPath, projectEnvVars: Map<String, String>?, environment: MirrordEnvironment) : MirrordCliTask<String>(cli, "verify-config", listOf("--ide", configPath.value), projectEnvVars, environment) {
        override fun compute(project: Project, process: Process, setText: (String) -> Unit): String {
            val logsService = project.service<MirrordLogsService>()

            setText(MIRRORD_VERIFYING_CONFIG_MESSAGE)
            logsService.logInfo(MIRRORD_VERIFYING_CONFIG_MESSAGE)

            process.waitFor()
            if (process.exitValue() != 0) {
                failFromExit(logsService, process, message = ::getConfigVerificationFailedError)
            }

            val bufferedReader = process.inputStream.reader().buffered()
            val stderr = process.errorStream.reader().buffered()
            val stderrText = stderr.readText()
            MirrordLogger.logger.debug(stderrText)
            if (stderrText.isNotBlank()) {
                logErrorToBoth(logsService, "Config verification stderr: $stderrText")
            }

            logsService.logInfo(MIRRORD_VERIFIED_CONFIG_MESSAGE)
            return bufferedReader.readText()
        }
    }

    /**
     * Executes the `mirrord verify-config [path]` task.
     *
     * @return String containing a json with either a success + warnings, or the verified config errors.
     */
    fun verifyConfig(
        cli: TargetPath,
        configFilePath: TargetPath,
        environment: MirrordEnvironment
    ): String {
        return MirrordVerifyConfigTask(cli, configFilePath, projectEnvVars, environment).run(service.project)
    }

    /**
     * Runs `mirrord ext` command to get the environment.
     * Displays a modal progress dialog.
     *
     * @return environment for the user's application
     */
    fun exec(cli: TargetPath, target: MirrordExecDialog.UserSelection, configFile: TargetPath?, executable: String?, environment: MirrordEnvironment): MirrordExecution {
        bumpRunCounter()

        val task = MirrordExtTask(cli, projectEnvVars, environment).apply {
            this.target = target.target
            this.namespace = target.namespace
            this.configFile = configFile
            this.executable = executable
        }

        val result = task.run(service.project)

        result.usesOperator?.let { usesOperator ->
            if (usesOperator) {
                MirrordSettingsState.instance.mirrordState.operatorUsed = true
            }
        }

        return result
    }

    fun containerExec(cli: TargetPath, target: MirrordExecDialog.UserSelection, configFile: TargetPath?, environment: MirrordEnvironment): MirrordContainerExecution {
        bumpRunCounter()

        val task = MirrordContainerExtTask(cli, projectEnvVars, environment).apply {
            this.target = target.target
            this.namespace = target.namespace
            this.configFile = configFile
        }

        val result = task.run(service.project)

        result.usesOperator?.let { usesOperator ->
            if (usesOperator) {
                MirrordSettingsState.instance.mirrordState.operatorUsed = true
            }
        }

        return result
    }

    private class MirrordAttachTask(cli: TargetPath, private val pid: Long, projectEnvVars: Map<String, String>?, environment: MirrordEnvironment) : MirrordCliTask<MirrordAttachExecution>(cli, "attach", listOf(pid.toString()), projectEnvVars, environment) {
        // `mirrord attach` injects the layer DLL into the target process (pid)
        // and exits 0 on success. Callers must run `mirrord ext` first to start
        // the intproxy and set env vars on the target.
        // stdout is drained to the log; exit code is the success signal.
        override fun compute(project: Project, process: Process, setText: (String) -> Unit): MirrordAttachExecution {
            val logsService = project.service<MirrordLogsService>()
            logsService.onMirrordExecutionStart()

            setText("mirrord is attaching to process $pid...")
            logsService.logInfo("mirrord is attaching to process $pid...")

            process.inputStream.reader().buffered().useLines { lines ->
                for (line in lines) {
                    if (line.isNotBlank()) {
                        logsService.logInfo("[mirrord attach] $line")
                    }
                }
            }

            process.waitFor()
            if (process.exitValue() != 0) {
                failFromExit(logsService, process, endsExecution = true) { "Attach process failed with stderr: $it" }
            }

            MirrordLogger.logger.info("mirrord attach exited with code 0, layer injected into pid $pid")
            setText("mirrord layer injected into process $pid")
            logsService.logInfo("mirrord attach completed successfully for process $pid")
            logsService.onMirrordExecutionEnd()
            return MirrordAttachExecution(mutableMapOf(), null)
        }
    }

    fun attach(cli: TargetPath, pid: Long, environment: MirrordEnvironment): MirrordAttachExecution {
        bumpRunCounter()

        // Only PID is passed — target, namespace, config flags remain null.
        // `mirrord ext` must have been run first by the caller.
        val task = MirrordAttachTask(cli, pid, projectEnvVars, environment)

        val result = task.run(service.project)

        result.usesOperator?.let { usesOperator ->
            if (usesOperator) {
                MirrordSettingsState.instance.mirrordState.operatorUsed = true
            }
        }

        return result
    }

    /**
     * Increments the mirrord run counter.
     * Can display some notifications (asking for feedback, slack invite, mirrord for Teams invite, newsletter signup).
     */
    private fun bumpRunCounter() {
        val previousRuns = MirrordSettingsState.instance.mirrordState.runsCounter
        val currentRuns = previousRuns + 1
        MirrordSettingsState.instance.mirrordState.runsCounter = currentRuns

        val operatorUsed = MirrordSettingsState.instance.mirrordState.operatorUsed

        if ((currentRuns % FEEDBACK_COUNTER_REVIEW_AFTER) == 0) {
            service.notifier.notification("Enjoying mirrord? Don't forget to leave a review or star us on GitHub!", NotificationType.INFORMATION).withLink("Review", "https://plugins.jetbrains.com/plugin/19772-mirrord/reviews").withLink("Star us on GitHub", GITHUB_URL).withDontShowAgain(MirrordSettingsState.NotificationId.PLUGIN_REVIEW).fire()
        }

        if (currentRuns == SLACK_COUNTER_INVITE_AFTER) {
            service.notifier.notification("Need any help with mirrord? Come chat with our team on Slack!", NotificationType.INFORMATION).withLink("Join us", "https://metalbear.com/slack").withDontShowAgain(MirrordSettingsState.NotificationId.SLACK_INVITE).fire()
        }

        if (previousRuns >= MIRRORD_FOR_TEAMS_INVITE_AFTER && !operatorUsed) {
            if ((previousRuns - MIRRORD_FOR_TEAMS_INVITE_AFTER) % MIRRORD_FOR_TEAMS_INVITE_EVERY == 0) {
                service.notifier.notification("mirrord for Teams unlocks team workflow features: DB branching for parallel devs, preview environments for branch testing, and shared targets with queue splitting.", NotificationType.INFORMATION).withLink("Try it now", MIRRORD_FOR_TEAMS_URL).withDontShowAgain(MirrordSettingsState.NotificationId.MIRRORD_FOR_TEAMS).fire()
            }
        }

        when (currentRuns) {
            NEWSLETTER_COUNTER_PROMPT_AFTER_FIRST -> "Liking what mirrord can do?\nStay in the loop with updates, tips & tricks straight from the team."
            NEWSLETTER_COUNTER_PROMPT_AFTER_SECOND -> "Looks like you're doing some serious work with mirrord!\nWant to hear about advanced features, upcoming releases, and cool use cases?"
            else -> null
        }?.let {
            service.notifier.notification(
                it,
                NotificationType.INFORMATION
            ).withLink("Subscribe to the mirrord newsletter", NEWSLETTER_SIGNUP_URL + "$currentRuns")
                .withDontShowAgain(MirrordSettingsState.NotificationId.NEWSLETTER_SIGNUP).fire()
        }
    }
}

/**
 * A mirrord CLI invocation.
 *
 * @param args: An extra list of arguments (used by `verify-config`).
 */
private abstract class MirrordCliTask<T>(
    private val cli: TargetPath,
    private val command: String,
    private val args: List<String>?,
    private val projectEnvVars: Map<String, String>?,
    private val environment: MirrordEnvironment
) {
    /**
     * Set when the plugin itself kills the process.
     *
     * Every kill path already reports its own reason: a cancel warning, a task failure, or a
     * timeout. The compute thread sees the resulting non-zero exit afterwards, so without this it
     * reports the same event a second time as a crash — including an IDE error report naming the
     * plugin for something the plugin did on purpose.
     */
    @Volatile
    private var abortedByPlugin = false

    /**
     * The only way this class kills a process.
     *
     * Routed through one place so the flag cannot be missed. It was, for four of six kill paths.
     */
    private fun abort(process: Process) {
        abortedByPlugin = true
        process.destroy()
    }

    /**
     * Reports a non-zero exit, unless the plugin caused it.
     *
     * Shared because this epilogue was identical at five call sites, and the abort check has to
     * come first at every one of them.
     */
    protected fun failFromExit(
        logsService: MirrordLogsService,
        process: Process,
        endsExecution: Boolean = false,
        message: (String) -> String
    ): Nothing {
        if (abortedByPlugin) {
            throw ProcessCanceledException()
        }

        val stdErr = process.errorStream.bufferedReader().readText()
        logErrorToBoth(logsService, message(stdErr))
        if (endsExecution) {
            logsService.onMirrordExecutionEnd()
        }
        throw MirrordError.fromStdErr(stdErr)
    }

    var target: String? = null
    var namespace: String? = null
    var configFile: TargetPath? = null
    var executable: String? = null
    var output: String? = null

    /**
     * Builds the invocation.
     *
     * The environment map is complete when this returns — nothing is added to it afterwards.
     * That is deliberate: the old code built a command line, patched it for WSL partway
     * through, and then kept adding variables, so `MIRRORD_PROGRESS_MODE` and friends were
     * registered for WSL interop only if statement order happened to cooperate.
     */
    private fun prepareSpec(project: Project): MirrordProcessSpec {
        val commandArgs = buildList {
            add(command)
            target?.let { add("-t"); add(it) }
            configFile?.let { add("-f"); add(it.value) }
            executable?.let { add("-e"); add(it) }
            output?.let { add("-o"); add(it) }
            args?.let { addAll(it) }
        }

        val env = buildMap {
            // Merge our vars with what is set in the current launch run configuration.
            projectEnvVars?.let { putAll(it) }
            namespace?.let { put("MIRRORD_TARGET_NAMESPACE", it) }
            // for config explanation to be printed out
            put("MIRRORD_EXT_PRINT_CONFIG", "TRUE")
            gitBranchOf(project)?.let { put("MIRRORD_BRANCH_NAME", it) }
            put("MIRRORD_PROGRESS_MODE", "json")
            put("MIRRORD_PROGRESS_SUPPORT_IDE", "true")
            put("MIRRORD_IDE_NAME", "intellij")
        }

        val spec = MirrordProcessSpec(cli, commandArgs, env, workingDirectory = null)
        project.service<MirrordLogsService>().logInfo("Executing mirrord command: ${spec.describe()}")
        return spec
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
        val spec = prepareSpec(project)
        val logsService = project.service<MirrordLogsService>()
        val taskTimeoutMinutes = MirrordSettingsState.instance.mirrordState.taskTimeoutMinutes.toLong()

        MirrordLogger.logger.info("running mirrord task in ${environment.name}: ${spec.describe()}")

        val process = environment.spawn(spec)

        return if (ApplicationManager.getApplication().isDispatchThread) {
            // Modal dialog with progress is very visible and can be canceled by the user,
            // so we don't use any timeout here.
            ProgressManager.getInstance().run(object : Task.WithResult<T, Exception>(project, "mirrord", true) {
                override fun compute(indicator: ProgressIndicator): T {
                    return computeWithResponsiveCancel(project, process, IndicatorProgressChecker(indicator))
                }

                override fun onCancel() {
                    val cancelMessage = getMirrordTaskCancelledMessage(spec.describe())
                    MirrordLogger.logger.warn(cancelMessage)
                    logsService.logWarning(cancelMessage)
                    abort(process)
                }

                override fun onThrowable(error: Throwable) {
                    val errorMessage = getMirrordTaskFailedError(spec.describe(), error)
                    logErrorToBoth(logsService, errorMessage)
                    abort(process)
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
                    val cancelMessage = getMirrordBackgroundTaskCancelledMessage(spec.describe())
                    MirrordLogger.logger.warn(cancelMessage)
                    logsService.logWarning(cancelMessage)
                    abort(process)
                    env.cancel(true)
                }

                override fun onThrowable(error: Throwable) {
                    val errorMessage = getMirrordBackgroundTaskFailedError(spec.describe(), error)
                    logErrorToBoth(logsService, errorMessage)
                    abort(process)
                    env.completeExceptionally(error)
                }
            })

            try {
                env.get(taskTimeoutMinutes, TimeUnit.MINUTES)
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            } catch (e: CancellationException) {
                throw ProcessCanceledException(e)
            } catch (e: TimeoutException) {
                abort(process)
                val errorMessage = getMirrordTaskTimedOutError(spec.describe())
                logErrorToBoth(logsService, errorMessage)
                throw MirrordError("mirrord process timed out")
            }
        } else {
            // Not on the EDT thread and under a read lock.
            // Cannot spawn a background task here, because it schedules start on the EDT thread
            // (to update the UI with a progress indicator).
            // EDT thread requires a write lock, so using a background task here would cause a deadlock.
            try {
                computeWithResponsiveCancel(project, process, TimeoutProgressChecker(taskTimeoutMinutes, TimeUnit.MINUTES))
            } catch (e: ProcessCanceledException) {
                // In this case, process is canceled only after a timeout.
                abort(process)
                val errorMessage = getMirrordTaskTimedOutUnderReadLockError(spec.describe())
                logErrorToBoth(logsService, errorMessage)
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

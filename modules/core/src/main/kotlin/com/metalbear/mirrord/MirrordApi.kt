@file:Suppress("DialogTitleCapitalization")

package com.metalbear.mirrord

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.wsl.WSLCommandLineOptions
import com.intellij.execution.wsl.WSLDistribution
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import java.util.concurrent.TimeUnit

enum class MessageType {
    NewTask,
    FinishedTask,
    Warning
}

// I don't know how to do tags like Rust so this format is for parsing both kind of messages ;_;
data class Message(
    val type: MessageType,
    val name: String,
    val parent: String?,
    val success: Boolean?,
    val message: String?
)

data class Error(
    val message: String,
    val severity: String,
    val causes: List<String>,
    val help: String,
    val labels: List<String>,
    val related: List<String>
)

data class MirrordExecution(
    val environment: MutableMap<String, String>,
    @SerializedName("patched_path")
    val patchedPath: String?
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
            MirrordLogger.logger.debug("failed to parse mirrord binary message", e)
            throw MirrordError(
                "failed to parse a message from the mirrord binary, try updating to the latest version",
                e
            )
        }
    }
}

/**
 * How many times mirrord can be run before displaying a notification asking for marketplace review.
 */
private const val FEEDBACK_COUNTER_REVIEW_AFTER = 100

/**
 * Interact with mirrord CLI using this API.
 */
class MirrordApi(private val service: MirrordProjectService) {
    private class MirrordLsTask(private val options: Options, private val project: Project) : Task.WithResult<List<String>, Exception>(project, "mirrord", true) {
        class Options(val cli: String) {
            var configFile: String? = null
            var wslDistribution: WSLDistribution? = null
        }

        var process: Process? = null

        private fun prepareCommandLine(): GeneralCommandLine {
            MirrordLogger.logger.debug("preparing commandline for mirrord ls")
            return GeneralCommandLine(options.cli, "ls", "-o", "json").apply {
                options.configFile?.let {
                    MirrordLogger.logger.debug("adding configFile to command line")
                    addParameter("-f")
                    val formattedPath = options.wslDistribution?.getWslPath(it) ?: it
                    addParameter(formattedPath)
                }

                options.wslDistribution?.let {
                    MirrordLogger.logger.debug("patching to use WSL")
                    val wslOptions = WSLCommandLineOptions()
                    wslOptions.isLaunchWithWslExe = true
                    it.patchCommandLine(this, project, wslOptions)
                }
            }
        }

        override fun compute(indicator: ProgressIndicator): List<String> {
            val commandLine = prepareCommandLine()
            MirrordLogger.logger.info("running mirrord with following command line: ${commandLine.commandLineString}")

            val process = commandLine.toProcessBuilder()
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                .redirectError(ProcessBuilder.Redirect.PIPE)
                .start()
            this.process = process

            indicator.text = "mirrord is listing targets..."
            var finished = false
            while (!finished) {
                indicator.checkCanceled()
                finished = process.waitFor(500, TimeUnit.MILLISECONDS)
            }

            if (process.exitValue() != 0) {
                val processStdError = process.errorStream.bufferedReader().readText()
                throw MirrordError.fromStdErr(processStdError)
            }

            val data = process.inputStream.bufferedReader().readText()
            MirrordLogger.logger.debug("parsing mirrord ls output: $data")

            val pods = SafeParser()
                .parse(data, Array<String>::class.java)
                .toMutableList()

            if (pods.isEmpty()) {
                project.service<MirrordProjectService>().notifier.notifySimple(
                    "No mirrord target available in the configured namespace. " +
                        "You can run targetless, or set a different target namespace or kubeconfig in the mirrord configuration file.",
                    NotificationType.INFORMATION
                )
            }

            return pods
        }

        override fun onCancel() {
            this.process?.destroy()
        }

        override fun onThrowable(error: Throwable) {
            MirrordLogger.logger.error("mirrord ls failed", error)
            this.process?.destroy()
        }
    }

    /**
     * Runs `mirrord ls` to get the list of available targets.
     * Displays a modal progress dialog.
     *
     * @return list of pods
     */
    fun listPods(
        cli: String,
        configFile: String?,
        wslDistribution: WSLDistribution?
    ): List<String> {
        val options = MirrordLsTask.Options(cli).apply {
            this.configFile = configFile
            this.wslDistribution = wslDistribution
        }

        val task = MirrordLsTask(options, service.project)

        return ProgressManager.getInstance().run(task)
    }

    class MirrordExtTask(private val options: Options, private val project: Project) : Task.WithResult<MirrordExecution, Exception>(project, "mirrord", true) {
        class Options(val cli: String) {
            var target: String? = null
            var configFile: String? = null
            var executable: String? = null
            var wslDistribution: WSLDistribution? = null
        }

        private var process: Process? = null

        private fun prepareCommandLine(): GeneralCommandLine {
            MirrordLogger.logger.debug("preparing commandline for mirrord ext")
            return GeneralCommandLine(options.cli, "ext").apply {
                options.target?.let {
                    addParameter("-t")
                    addParameter(it)
                }

                options.configFile?.let {
                    val formattedPath = options.wslDistribution?.getWslPath(it) ?: it
                    addParameter("-f")
                    addParameter(formattedPath)
                }
                options.executable?.let {
                    addParameter("-e")
                    addParameter(it)
                }

                options.wslDistribution?.let {
                    val wslOptions = WSLCommandLineOptions().apply {
                        isLaunchWithWslExe = true
                    }
                    it.patchCommandLine(this, project, wslOptions)
                }

                environment["MIRRORD_PROGRESS_MODE"] = "json"
            }
        }
        override fun compute(indicator: ProgressIndicator): MirrordExecution {
            val commandLine = prepareCommandLine()
            MirrordLogger.logger.info("running mirrord with following command line: ${commandLine.commandLineString}")

            val process = commandLine.toProcessBuilder()
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                .redirectError(ProcessBuilder.Redirect.PIPE)
                .start()
            this.process = process

            val parser = SafeParser()
            val bufferedReader = process.inputStream.reader().buffered()

            val warningHandler = MirrordWarningHandler(project.service<MirrordProjectService>())

            indicator.text = "mirrord is starting..."
            for (line in bufferedReader.lines()) {
                indicator.checkCanceled()
                val message = parser.parse(line, Message::class.java)
                when {
                    message.name == "mirrord preparing to launch" && message.type == MessageType.FinishedTask -> {
                        val success = message.success ?: throw MirrordError("invalid message received from the mirrord binary")
                        if (success) {
                            val innerMessage = message.message ?: throw MirrordError("invalid message received from the mirrord binary")
                            val executionInfo = parser.parse(innerMessage, MirrordExecution::class.java)
                            indicator.text = "mirrord is running"
                            return executionInfo
                        }
                    }

                    message.type == MessageType.Warning -> {
                        message.message?.let { warningHandler.handle(it) }
                    }

                    else -> {
                        var displayMessage = message.name
                        message.message?.let {
                            displayMessage += ": $it"
                        }
                        indicator.text = displayMessage
                    }
                }
            }

            throw MirrordError("invalid output of the mirrord binary")
        }

        override fun onSuccess() {
            project.service<MirrordProjectService>().notifier.notifySimple("mirrord starting...", NotificationType.INFORMATION)
        }

        override fun onCancel() {
            this.process?.destroy()
        }

        override fun onThrowable(error: Throwable) {
            this.process?.destroy()
        }
    }

    /**
     * Runs `mirrord ext` command to get the environment.
     * Displays a modal progress dialog.
     *
     * @return environment for the user's application
     */
    fun exec(
        cli: String,
        target: String?,
        configFile: String?,
        executable: String?,
        wslDistribution: WSLDistribution?
    ): MirrordExecution {
        bumpFeedbackCounter()

        val options = MirrordExtTask.Options(cli).apply {
            this.target = target
            this.configFile = configFile
            this.executable = executable
            this.wslDistribution = wslDistribution
        }

        val task = MirrordExtTask(options, service.project)

        return ProgressManager.getInstance().run(task)
    }

    /**
     * Increments the mirrord run counter. Occasionally displays a notification asking for marketplace review.
     */
    private fun bumpFeedbackCounter() {
        val previousRuns = MirrordSettingsState.instance.mirrordState.runsCounter
        val currentRuns = previousRuns + 1

        MirrordSettingsState.instance.mirrordState.runsCounter = currentRuns

        if ((currentRuns % FEEDBACK_COUNTER_REVIEW_AFTER) != 0) {
            return
        }

        service.notifier.notification(
            "Enjoying mirrord? Don't forget to leave a review! Also consider giving us some feedback, we'd highly appreciate it!",
            NotificationType.INFORMATION
        )
            .withLink(
                "Review",
                "https://plugins.jetbrains.com/plugin/19772-mirrord/reviews"
            )
            .withLink("Feedback", FEEDBACK_URL)
            .withDontShowAgain(MirrordSettingsState.NotificationId.PLUGIN_REVIEW)
            .fire()
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

    private val filters: List<WarningFilter> = listOf(
        WarningFilter(
            { message -> message.contains("Agent version") && message.contains("does not match the local mirrord version") },
            MirrordSettingsState.NotificationId.AGENT_VERSION_MISMATCH
        )
    )

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

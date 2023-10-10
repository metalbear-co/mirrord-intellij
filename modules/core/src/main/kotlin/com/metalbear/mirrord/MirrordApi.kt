@file:Suppress("DialogTitleCapitalization")

package com.metalbear.mirrord

import com.google.gson.Gson
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
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

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
    private class MirrordLsTask(cli: String) : MirrordCliTask<List<String>>(cli, "ls", null) {
        override fun compute(project: Project, process: Process, setText: (String) -> Unit): List<String> {
            setText("mirrord is listing targets...")

            process.waitFor()
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
        val task = MirrordLsTask(cli).apply {
            this.configFile = configFile
            this.wslDistribution = wslDistribution
            this.output = "json"
        }

        return task.run(service.project)
    }

    private class MirrordExtTask(cli: String) : MirrordCliTask<MirrordExecution>(cli, "ext", null) {
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
                            val executionInfo = parser.parse(innerMessage, MirrordExecution::class.java)
                            setText("mirrord is running")
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
    private class MirrordVerifyConfigTask(cli: String, path: String) : MirrordCliTask<String>(cli, "verify-config", listOf("--ide", path)) {
        override fun compute(project: Project, process: Process, setText: (String) -> Unit): String {
            setText("mirrord is verifying the config options...")
            process.waitFor()
            if (process.exitValue() != 0) {
                val processStdError = process.errorStream.bufferedReader().readText()
                throw MirrordError.fromStdErr(processStdError)
            }

            val parser = SafeParser()
            val bufferedReader = process.inputStream.reader().buffered()

            val warningHandler = MirrordWarningHandler(project.service<MirrordProjectService>())
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
        configFilePath: String
    ): String {
        return MirrordVerifyConfigTask(cli, configFilePath).run(service.project)
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

        val task = MirrordExtTask(cli).apply {
            this.target = target
            this.configFile = configFile
            this.executable = executable
            this.wslDistribution = wslDistribution
        }

        val result = task.run(service.project)
        service.notifier.notifySimple("mirrord starting...", NotificationType.INFORMATION)

        return result
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

/**
 * A mirrord CLI invocation.
 *
 * @param args: An extra list of arguments (used by `verify-config`).
 */
private abstract class MirrordCliTask<T>(private val cli: String, private val command: String, private val args: List<String>?) {
    var target: String? = null
    var configFile: String? = null
    var executable: String? = null
    var wslDistribution: WSLDistribution? = null
    var output: String? = null

    /**
     * Returns command line for execution.
     */
    private fun prepareCommandLine(project: Project): GeneralCommandLine {
        return GeneralCommandLine(cli, command).apply {
            target?.let {
                addParameter("-t")
                addParameter(it)
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
                }
                it.patchCommandLine(this, project, wslOptions)
            }

            output?.let {
                addParameter("-o")
                addParameter(it)
            }

            args?.let { extraArgs -> extraArgs.forEach { addParameter(it) } }

            environment["MIRRORD_PROGRESS_MODE"] = "json"
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
    private fun computeWithResponsiveCancel(project: Project, process: Process, indicator: ProgressIndicator): T {
        val result = CompletableFuture<T>()

        // There is a version of this method that takes a `Callable<T>`, but its implementation is broken.
        // Therefore, we use this one and `CompletableFuture<T>`.
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val computationResult = compute(project, process) { text -> indicator.text = text }
                result.complete(computationResult)
            } catch (e: Throwable) {
                if (!indicator.isCanceled) {
                    result.completeExceptionally(e)
                }
            }
        }

        while (true) {
            indicator.checkCanceled()

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

        val process = commandLine
            .toProcessBuilder()
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()

        return if (ApplicationManager.getApplication().isDispatchThread) {
            // Modal dialog with progress is very visible and can be canceled by the user,
            // so we don't use any timeout here.
            ProgressManager.getInstance().run(object : Task.WithResult<T, Exception>(project, "mirrord", true) {
                override fun compute(indicator: ProgressIndicator): T {
                    return computeWithResponsiveCancel(project, process, indicator)
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
        } else {
            val env = CompletableFuture<T>()

            ProgressManager.getInstance().run(object : Task.Backgroundable(project, "mirrord", true) {
                override fun run(indicator: ProgressIndicator) {
                    val res = computeWithResponsiveCancel(project, process, indicator)
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
        }
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

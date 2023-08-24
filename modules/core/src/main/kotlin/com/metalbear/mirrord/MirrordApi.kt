@file:Suppress("DialogTitleCapitalization")

package com.metalbear.mirrord

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.wsl.WSLCommandLineOptions
import com.intellij.execution.wsl.WSLDistribution
import com.intellij.notification.NotificationType
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import java.util.concurrent.CompletableFuture
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
    private val logger = MirrordLogger.logger

    /**
     * Runs `mirrord ls` to get the list of available targets.
     *
     * @return list of pods
     */
    fun listPods(
        cli: String,
        configFile: String?,
        wslDistribution: WSLDistribution?
    ): List<String> {
        logger.debug("listing pods")

        val commandLine = GeneralCommandLine(cli, "ls", "-o", "json")
        configFile?.let {
            logger.debug("adding configFile to command line")
            commandLine.addParameter("-f")
            val formattedPath = wslDistribution?.getWslPath(it) ?: it
            commandLine.addParameter(formattedPath)
        }

        wslDistribution?.let {
            logger.debug("patching to use WSL")
            val wslOptions = WSLCommandLineOptions()
            wslOptions.isLaunchWithWslExe = true
            it.patchCommandLine(commandLine, service.project, wslOptions)
        }

        logger.debug("creating command line and executing $commandLine")

        val process = commandLine.toProcessBuilder()
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()

        logger.debug("waiting for process to finish")
        process.waitFor(60, TimeUnit.SECONDS)

        logger.debug("process wait finished, reading output")

        if (process.exitValue() != 0) {
            val processStdError = process.errorStream.bufferedReader().readText()
            throw MirrordError.fromStdErr(processStdError)
        }

        val data = process.inputStream.bufferedReader().readText()
        logger.debug("parsing $data")

        val pods = SafeParser()
            .parse(data, Array<String>::class.java)
            .toMutableList()

        if (pods.isEmpty()) {
            service.notifier.notifySimple(
                "No mirrord target available in the configured namespace. " +
                    "You can run targetless, or set a different target namespace or kubeconfig in the mirrord configuration file.",
                NotificationType.INFORMATION
            )
        }

        return pods
    }

    fun exec(
        cli: String,
        target: String?,
        configFile: String?,
        executable: String?,
        wslDistribution: WSLDistribution?
    ): MirrordExecution {
        bumpFeedbackCounter()

        val commandLine = GeneralCommandLine(cli, "ext").apply {
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
                addParameter(executable)
            }

            wslDistribution?.let {
                val wslOptions = WSLCommandLineOptions().apply {
                    isLaunchWithWslExe = true
                }
                it.patchCommandLine(this, service.project, wslOptions)
            }

            environment["MIRRORD_PROGRESS_MODE"] = "json"
        }

        logger.info("running mirrord with following command line: ${commandLine.commandLineString}")

        val process = commandLine.toProcessBuilder()
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()

        val parser = SafeParser()
        val bufferedReader = process.inputStream.reader().buffered()

        val environment = CompletableFuture<MirrordExecution>()

        val mirrordProgressTask = object : Task.Backgroundable(service.project, "mirrord", true) {
            override fun run(indicator: ProgressIndicator) {
                val warningHandler = MirrordWarningHandler(service)

                indicator.text = "mirrord is starting..."
                for (line in bufferedReader.lines()) {
                    val message = parser.parse(line, Message::class.java)
                    when {
                        message.name == "mirrord preparing to launch" && message.type == MessageType.FinishedTask -> {
                            val success = message.success ?: throw MirrordError("invalid message received from the mirrord binary")
                            if (success) {
                                val innerMessage = message.message ?: throw MirrordError("invalid message received from the mirrord binary")
                                val executionInfo = parser.parse(innerMessage, MirrordExecution::class.java)
                                indicator.text = "mirrord is running"
                                environment.complete(executionInfo)
                                return
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
                environment.cancel(true)
            }

            override fun onSuccess() {
                if (!environment.isCancelled) {
                    service.notifier.notifySimple("mirrord starting...", NotificationType.INFORMATION)
                }
            }

            override fun onCancel() {
                process.destroy()
                service.notifier.notifySimple("mirrord was cancelled", NotificationType.WARNING)
            }

            /**
             * Prevents IntelliJ from showing error notification
             */
            override fun onThrowable(error: Throwable) {}
        }

        ProgressManager.getInstance().run(mirrordProgressTask)

        try {
            return environment.get(2, TimeUnit.MINUTES)
        } catch (e: Throwable) {
            throw MirrordError("failed to fetch the env", e)
        }
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

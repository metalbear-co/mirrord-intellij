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
    class ParseError(e: Exception) : Exception() {
        override val message = "failed to parse a message from the mirrord binary, try updating to the latest version"
        override val cause = e
    }

    private val gson = Gson()

    /**
     * @throws ParseError
     */
    fun <T> parse(value: String, classOfT: Class<T>): T {
        return try {
            gson.fromJson(value, classOfT)
        } catch (e: Exception) {
            MirrordLogger.logger.debug("failed to parse mirrord binary message", e)
            throw ParseError(e)
        }
    }
}

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
    ): List<String>? {
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

        // failure -> error
        // success -> empty -> targetless, else -> list of pods + targetless
        val gson = Gson()
        if (process.exitValue() != 0) {
            val processStdError = process.errorStream.bufferedReader().readText()
            if (processStdError.startsWith("Error: ")) {
                val trimmedError = processStdError.removePrefix("Error: ")
                val error = gson.fromJson(trimmedError, Error::class.java)
                service.notifier.notifyRichError(error.message)
                service.notifier.notifySimple(error.help, NotificationType.INFORMATION)
            }
            logger.error("mirrord ls failed: $processStdError")
            return null
        }

        val data = process.inputStream.bufferedReader().readText()
        logger.debug("parsing $data")
        val pods = gson.fromJson(data, Array<String>::class.java).toMutableList()

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
    ): MirrordExecution? {
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
        }

        logger.info("running mirrord with following command line: ${commandLine.commandLineString}")

        val process = commandLine.toProcessBuilder()
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()

        val parser = SafeParser()
        val bufferedReader = process.inputStream.reader().buffered()

        val environment = CompletableFuture<MirrordExecution?>()

        val mirrordProgressTask = object : Task.Backgroundable(service.project, "mirrord", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "mirrord is starting..."
                for (line in bufferedReader.lines()) {
                    val message = parser.parse(line, Message::class.java)
                    when {
                        message.name == "mirrord preparing to launch" && message.type == MessageType.FinishedTask -> {
                            val success = message.success ?: throw Error("Invalid message")
                            if (success) {
                                val innerMessage = message.message ?: throw Error("Invalid inner message")
                                val executionInfo = parser.parse(innerMessage, MirrordExecution::class.java)
                                indicator.text = "mirrord is running"
                                environment.complete(executionInfo)
                                return
                            }
                        }

                        message.type == MessageType.Warning -> {
                            message.message?.let { service.notifier.notifySimple(it, NotificationType.WARNING) }
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
            return environment.get(30, TimeUnit.SECONDS)
        } catch (e: Exception) {
            var message = "mirrord failed to fetch the env"
            logger.debug(message, e)
            e.message?.let { message += ": $it" }
            service.notifier.notifyRichError(message)
        }

        val processStdError = process.errorStream.reader().readText()
        if (processStdError.startsWith("Error: ")) {
            val trimmedError = processStdError.removePrefix("Error: ")

            try {
                val error = parser.parse(trimmedError, Error::class.java)
                service.notifier.notifyRichError(error.message)
                service.notifier.notifySimple(error.help, NotificationType.INFORMATION)
            } catch (e: SafeParser.ParseError) {
                service.notifier.notifyRichError(trimmedError)
                service.notifier.notifySimple(e.message, NotificationType.WARNING)
            }

            return null
        }

        logger.error("mirrord stderr: $processStdError")
        throw Error("mirrord failed to start")
    }
}

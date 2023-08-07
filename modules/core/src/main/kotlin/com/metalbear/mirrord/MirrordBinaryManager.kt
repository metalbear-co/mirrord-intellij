package com.metalbear.mirrord

import com.intellij.execution.wsl.WSLDistribution
import com.intellij.notification.NotificationType
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.system.CpuArch
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.Charset
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

private const val CLI_BINARY = "mirrord"
private const val VERSION_ENDPOINT = "https://version.mirrord.dev/v1/version"
private const val DOWNLOAD_ENDPOINT = "https://github.com/metalbear-co/mirrord/releases/download"

/**
 * For dynamically fetching and storing mirrord binary.
 */
class MirrordBinaryManager(val service: MirrordProjectService) {
    @Volatile
    private var latestSupportedVersion: String? = null

    private val downloadTaskRunning: AtomicBoolean = AtomicBoolean(false)

    init {
        VersionCheckTask(this, null).queue()
    }

    /**
     * Background task for checking the latest supported version of the mirrord binary.
     */
    private class VersionCheckTask(private val manager: MirrordBinaryManager, val product: String?) : Task.Backgroundable(manager.service.project, "mirrord", true) {
        override fun run(indicator: ProgressIndicator) {
            val pluginVersion = if (System.getenv("CI_BUILD_PLUGIN") == "true" ||
                System.getenv("PLUGIN_TESTING_ENVIRONMENT") == "true") {
                "test"
            } else {
                VERSION ?: "unknown"
            }

            indicator.text = "mirrord is checking the latest supported binary version..."

            val url = StringBuilder(VERSION_ENDPOINT)
                .append("?source=3")
                .append("&version=")
                .append(URLEncoder.encode(pluginVersion, Charset.defaultCharset()))
                .append("&platform=")
                .append(URLEncoder.encode(SystemInfo.OS_NAME, Charset.defaultCharset()))
                .toString()

            val client = HttpClient.newHttpClient()
            val builder = HttpRequest
                .newBuilder(URI(url))
                .timeout(Duration.ofSeconds(10L))
                .GET()

            product?.let { builder.header("user-agent", it) }

            val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            manager.latestSupportedVersion = response.body()
        }

        override fun onThrowable(error: Throwable) {
            MirrordLogger.logger.debug("binary version check failed", error)
            manager.service.notifier.notifyRichError(
                "failed to check the latest supported version of the mirrord binary"
            )
        }
    }

    /**
     * Background task for downloading the selected version of the mirrord binary.
     */
    private class BinaryDownloadTask(private val manager: MirrordBinaryManager, val version: String) : Task.Backgroundable(manager.service.project, "mirrord", true) {
        override fun run(indicator: ProgressIndicator) {
            val url = if (SystemInfo.isMac) {
                "$DOWNLOAD_ENDPOINT/$version/mirrord_mac_universal"
            } else if (SystemInfo.isLinux || SystemInfo.isWindows) {
                if (CpuArch.isArm64()) {
                    "$DOWNLOAD_ENDPOINT/$version/mirrord_linux_aarch64"
                } else if (CpuArch.isIntel64()) {
                    "$DOWNLOAD_ENDPOINT/$version/mirrord_linux_x86_64"
                } else {
                    throw RuntimeException("Unsupported architecture: " + CpuArch.CURRENT.name)
                }
            } else {
                throw RuntimeException("Unsupported platform: " + SystemInfo.OS_NAME)
            }

            indicator.text = "mirrord is downloading binary version $version..."
            indicator.fraction = 0.0

            val connection = URI(url).toURL().openConnection()
            connection.connect()
            val size = connection.contentLength
            val stream = connection.getInputStream()

            val bytes = ByteArray(size)
            var bytesRead = 0
            while (bytesRead < size) {
                val toRead = minOf(4096, size - bytesRead)
                val readNow = stream.read(bytes, bytesRead, toRead)
                if (readNow == -1) {
                    break
                }
                bytesRead += readNow
                indicator.fraction = bytesRead.toDouble() / size.toDouble()
            }

            stream.close()

            val destination = MirrordPathManager.getPath(CLI_BINARY, true)
            Files.createDirectories(destination.parent)
            Files.write(destination, bytes)
            destination.toFile().setExecutable(true)
        }

        override fun onThrowable(error: Throwable) {
            MirrordLogger.logger.debug("binary dowload failed", error)
            manager.service.notifier.notifyRichError("failed to download mirrord binary version $version")
        }

        override fun onFinished() {
            manager.downloadTaskRunning.set(false)
        }

        override fun onSuccess() {
            manager.service.notifier.notifySimple(
                "downloaded mirrord binary version $version",
                NotificationType.INFORMATION
            )
        }
    }

    private class MirrordBinary(val command: String) {
        val version: String

        init {
            val child = Runtime.getRuntime().exec(arrayOf(command, "--version"))

            val result = child.waitFor()
            if (result != 0) {
                MirrordLogger.logger.debug("`mirrord --version` failed with code $result")
                throw RuntimeException("failed to get mirrord version")
            }

            version = child.inputReader().readLine().split(' ')[1].trim()
        }
    }

    /**
     * @return executable found by `which mirrord`
     */
    private fun findBinaryInPath(wslDistribution: WSLDistribution?): MirrordBinary {
        return if (wslDistribution == null) {
            val child = Runtime.getRuntime().exec(arrayOf("which", "mirrord"))
            val result = child.waitFor()
            if (result != 0) {
                throw RuntimeException("`which` failed with code $result")
            }
            MirrordBinary(child.inputReader().readLine().trim())
        } else {
            val output = wslDistribution.executeOnWsl(1000, "which", "mirrord")
            if (output.exitCode != 0) {
                throw RuntimeException("`which` failed with code ${output.exitCode}")
            }
            MirrordBinary(output.stdoutLines.first().trim())
        }
    }

    /**
     * @return the local installation of mirrord, either in `PATH` or in plugin storage
     */
    private fun getLocalBinary(requiredVersion: String?, wslDistribution: WSLDistribution?): MirrordBinary? {
        try {
            val foundInPath = this.findBinaryInPath(wslDistribution)
            if (requiredVersion == null || requiredVersion == foundInPath.version) {
                return foundInPath
            }
        } catch (e: Exception) {
            MirrordLogger.logger.debug("failed to find mirrord in path", e)
        }

        try {
            MirrordPathManager.getBinary(CLI_BINARY, true)?.let {
                val binary = MirrordBinary(it)
                if (requiredVersion == null || requiredVersion == binary.version) {
                    return binary
                }
            }
        } catch (e: Exception) {
            MirrordLogger.logger.debug("failed to find mirrord in plugin storage", e)
        }

        return null
    }

    /**
     * Finds a local installation of the mirrord binary.
     * Schedules a binary version check to be executed in the background.
     * If the mirrord binary is not found, schedules a download to be executed in the background.
     *
     * @return the path to the binary
     *
     * @throws RuntimeException if the binary is not found
     */
    fun getBinary(product: String, wslDistribution: WSLDistribution?): String {
        VersionCheckTask(this, product).queue()

        latestSupportedVersion?.let { version ->
            getLocalBinary(version, wslDistribution)?.let { return it.command }

            if (!downloadTaskRunning.compareAndExchange(false, true)) {
                BinaryDownloadTask(this, version).queue()
            }
        }

        this.getLocalBinary(null, wslDistribution)?.let {
            var message = "using a local installation with version ${it.version}"
            latestSupportedVersion?.let { version ->
                message += ", latest supported version is $version"
            }

            service
                .notifier
                .notification(message, NotificationType.WARNING)
                .withDontShowAgain(MirrordSettingsState.NotificationId.POSSIBLY_OUTDATED_BINARY_USED)
                .fire()

            return it.command
        }

        val message = if (downloadTaskRunning.get()) {
            "no local installation found, downloading in the background"
        } else {
            "no local installation found"
        }
        throw RuntimeException(message)
    }
}

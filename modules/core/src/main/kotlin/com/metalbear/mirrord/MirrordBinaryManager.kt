package com.metalbear.mirrord

import com.intellij.execution.wsl.WSLDistribution
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.system.CpuArch
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.name

private const val CLI_BINARY = "mirrord"
private const val VERSION_ENDPOINT = "https://version.mirrord.dev/v1/version"
private const val DOWNLOAD_ENDPOINT = "https://github.com/metalbear-co/mirrord/releases/download"

/**
 * For dynamically fetching and storing mirrord binary.
 */
@Service(Service.Level.APP)
class MirrordBinaryManager {
    @Volatile
    private var latestSupportedVersion: String? = null

    /**
     * Schedules the update task at project startup.
     */
    class DownloadInitializer : StartupActivity.Background {
        override fun runActivity(project: Project) {
            UpdateTask(project, null, null, false).queue()
        }
    }

    /**
     * Runs version check and binary download in the background.
     *
     * @param product for example "idea", "goland", null if unknown
     * @param wslDistribution null if not applicable or unknown
     * @param checkInPath whether the task should attempt to find binary installation in PATH.
     *                    Should be false if wslDistribution is unknown
     */
    private class UpdateTask(
        private val project: Project,
        private val product: String?,
        private val wslDistribution: WSLDistribution?,
        private val checkInPath: Boolean
    ) : Task.Backgroundable(project, "mirrord", true), DumbAware {
        companion object State {
            /**
             * Only one download may be happening at the same time.
             */
            val downloadInProgress = AtomicBoolean(false)
        }

        /**
         * Binary version being downloaded by this task.
         */
        private var downloadingVersion: String? = null

        override fun run(indicator: ProgressIndicator) {
            indicator.isIndeterminate = false

            val manager = service<MirrordBinaryManager>()

            val autoUpdate = MirrordSettingsState.instance.mirrordState.autoUpdate
            val userSelectedMirrordVersion = MirrordSettingsState.instance.mirrordState.mirrordVersion

            val version = when {
                // auto update -> false -> use mirrordVersion if it's not empty
                !autoUpdate && userSelectedMirrordVersion.isNotEmpty() -> {
                    if (checkVersionFormat(userSelectedMirrordVersion)) {
                        userSelectedMirrordVersion
                    } else {
                        project
                            .service<MirrordProjectService>()
                            .notifier
                            .notification("mirrord version format is invalid!", NotificationType.WARNING)
                            .fire()
                        return
                    }
                }
                // auto update -> false -> mirrordVersion is empty -> needs check in the path
                // if not in path -> fetch latest version
                !autoUpdate && userSelectedMirrordVersion.isEmpty() -> null

                // auto update -> true -> fetch latest version
                else -> manager.fetchLatestSupportedVersion(product, indicator)
            }

            val local = if (checkInPath) {
                manager.getLocalBinary(version, wslDistribution)
            } else {
                manager.findBinaryInStorage(version)
            }

            if (local != null) {
                return
            }

            manager.latestSupportedVersion = version
                // auto update -> false -> mirrordVersion is empty -> no cli found locally -> fetch latest version
                ?: manager.fetchLatestSupportedVersion(product, indicator)

            if (downloadInProgress.compareAndExchange(false, true)) {
                return
            }

            downloadingVersion = version
            manager.updateBinary(indicator)
        }

        override fun onThrowable(error: Throwable) {
            MirrordLogger.logger.debug("binary update task failed", error)

            project.service<MirrordProjectService>()
                .notifier
                .notifyRichError("failed to update the mirrord binary: ${error.message ?: error.toString()}")
        }

        override fun onFinished() {
            if (downloadingVersion != null) {
                downloadInProgress.set(false)
            }
        }

        override fun onSuccess() {
            downloadingVersion?.let {
                project
                    .service<MirrordProjectService>()
                    .notifier
                    .notifySimple(
                        "downloaded mirrord binary version $downloadingVersion",
                        NotificationType.INFORMATION
                    )
            }
        }

        /**
         * checks if the passed version string matches *.*.* format (numbers only)
         * @param version version string to check
         * */
        fun checkVersionFormat(version: String): Boolean {
            return version.matches(Regex("^[0-9]+\\.[0-9]+\\.[0-9]+$"))
        }
    }

    private fun fetchLatestSupportedVersion(product: String?, indicator: ProgressIndicator): String {
        val pluginVersion = if (
            System.getenv("CI_BUILD_PLUGIN") == "true" ||
            System.getenv("PLUGIN_TESTING_ENVIRONMENT") == "true"
        ) {
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

        return response.body()
    }

    private fun updateBinary(indicator: ProgressIndicator) {
        val version = latestSupportedVersion ?: return

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
            indicator.checkCanceled()
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

        val tmpDestination = destination.resolveSibling(destination.name + UUID.randomUUID().toString())

        Files.write(tmpDestination, bytes)
        destination.toFile().setExecutable(true)
        Files.move(tmpDestination, destination, StandardCopyOption.REPLACE_EXISTING)
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
     * @return executable found with `which mirrord`
     */
    private fun findBinaryInPath(requiredVersion: String?, wslDistribution: WSLDistribution?): MirrordBinary {
        try {
            val output = if (wslDistribution == null) {
                val child = Runtime.getRuntime().exec(arrayOf("which", "mirrord"))
                val result = child.waitFor()
                if (result != 0) {
                    throw RuntimeException("`which` failed with code $result")
                }
                child.inputReader().readLine().trim()
            } else {
                val output = wslDistribution.executeOnWsl(1000, "which", "mirrord")
                if (output.exitCode != 0) {
                    throw RuntimeException("`which` failed with code ${output.exitCode}")
                }
                output.stdoutLines.first().trim()
            }

            val binary = MirrordBinary(output)
            if (requiredVersion == null || requiredVersion == binary.version) {
                return binary
            }
        } catch (e: Exception) {
            MirrordLogger.logger.debug("failed to find mirrord in path", e)
        }

        MirrordLogger.logger.debug("no mirrord found on path")
        return MirrordBinary("/home/meowjesty/dev/metalbear/mirrord/target/debug/mirrord")
    }

    /**
     * @return executable found in plugin storage
     */
    private fun findBinaryInStorage(requiredVersion: String?): MirrordBinary? {
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
     * @return the local installation of mirrord, either in `PATH` or in plugin storage
     */
    private fun getLocalBinary(requiredVersion: String?, wslDistribution: WSLDistribution?): MirrordBinary? {
        return findBinaryInPath(requiredVersion, wslDistribution) ?: findBinaryInStorage(requiredVersion)
    }

    /**
     * Finds a local installation of the mirrord binary.
     * Schedules a binary update task to be executed in the background.
     *
     * @throws MirrordError if no local binary was found
     * @return the path to the binary
     */
    fun getBinary(product: String, wslDistribution: WSLDistribution?, project: Project): String {
        UpdateTask(project, product, wslDistribution, true).queue()

        latestSupportedVersion?.let { version ->
            getLocalBinary(version, wslDistribution)?.let { return it.command }
        }

        this.getLocalBinary(null, wslDistribution)?.let {
            val message = latestSupportedVersion?.let { latest ->
                "using a local installation with version ${it.version}, latest supported version is $latest"
            } ?: "using a possibly outdated local installation with version ${it.version}"

            project
                .service<MirrordProjectService>()
                .notifier
                .notification(message, NotificationType.WARNING)
                .withDontShowAgain(MirrordSettingsState.NotificationId.POSSIBLY_OUTDATED_BINARY_USED)
                .fire()

            return it.command
        }

        throw MirrordError(
            "no local installation of mirrord binary was found",
            "mirrord binary will be downloaded in the background"
        )
    }
}

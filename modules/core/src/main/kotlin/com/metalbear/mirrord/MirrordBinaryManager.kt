package com.metalbear.mirrord

import com.intellij.execution.wsl.WSLDistribution
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
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
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

private const val CLI_BINARY = "mirrord"
private const val VERSION_ENDPOINT = "https://version.mirrord.dev/v1/version"
private const val DOWNLOAD_ENDPOINT = "https://github.com/metalbear-co/mirrord/releases/download"

/**
 * For dynamically fetching and storing mirrord binary.
 */
class MirrordBinaryManager(private val service: MirrordProjectService) {
    /**
     * @return version of mirrord binary
     *
     * @throws RuntimeException if `--version` command failed
     */
    private fun getVersion(cliPath: String): String {
        val child = Runtime.getRuntime().exec(arrayOf(cliPath, "--version"))

        val result = child.waitFor()
        if (result != 0) {
            MirrordLogger.logger.debug("`mirrord --version` failed with code $result")
            throw RuntimeException("failed to get mirrord version")
        }

        return child.inputReader().readLine().split(' ')[1].trim()
    }

    /**
     * @return executable found by `which mirrord`
     */
    private fun findBinaryInPath(wslDistribution: WSLDistribution?): String {
        return if (wslDistribution == null) {
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
    }

    /**
     * @return path to the local installation of mirrord
     */
    private fun getLocalBinary(requiredVersion: String?, wslDistribution: WSLDistribution?): String? {
        try {
            val foundInPath = this.findBinaryInPath(wslDistribution)
            if (requiredVersion == null || requiredVersion == this.getVersion(foundInPath)) {
                return foundInPath
            }
        } catch (e: Exception) {
            MirrordLogger.logger.debug("failed to find mirrord in path", e)
        }

        try {
            MirrordPathManager.getBinary(CLI_BINARY, true)?.let {
                if (requiredVersion == null || requiredVersion == this.getVersion(it)) {
                    return it
                }
            }
        } catch (e: Exception) {
            MirrordLogger.logger.debug("failed to find mirrord in plugin storage", e)
        }

        return null
    }

    private fun getLatestSupportedVersion(product: String, timeout: Duration): String {
        val environment = CompletableFuture<String>()
        val versionCheckTask = object : Task.Backgroundable(service.project, "mirrord", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "mirrord is checking the latest supported version..."

                val testing = System.getenv("CI_BUILD_PLUGIN") == "true" ||
                    System.getenv("PLUGIN_TESTING_ENVIRONMENT") == "true"
                val version = if (testing) {
                    "test"
                } else {
                    VERSION ?: "unknown"
                }

                val url = StringBuilder(versionEndpoint)
                    .append("?source=3")
                    .append("&version=")
                    .append(URLEncoder.encode(version, Charset.defaultCharset()))
                    .append("&platform=")
                    .append(URLEncoder.encode(SystemInfo.OS_NAME, Charset.defaultCharset()))
                    .toString()

                val client = HttpClient.newHttpClient()
                val request = HttpRequest
                    .newBuilder(URI(url))
                    .header("user-agent", product)
                    .timeout(timeout)
                    .GET()
                    .build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())

                environment.complete(response.body())
            }

            override fun onThrowable(error: Throwable) {
                MirrordLogger.logger.debug(error)
            }
        }

        ProgressManager.getInstance().run(versionCheckTask)

        return try {
            environment.get(timeout.seconds, TimeUnit.SECONDS)
        } catch (e: Exception) {
            throw RuntimeException("failed to check the latest supported version of mirrord", e)
        }
    }

    private fun downloadBinary(destination: Path, version: String) {
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

        val environment = CompletableFuture<ByteArray>()
        val versionCheckTask = object : Task.Backgroundable(service.project, "mirrord", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "mirrord is downloading version $version..."
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
                environment.complete(bytes)
            }

            override fun onThrowable(error: Throwable) {
                MirrordLogger.logger.debug(error)
            }
        }

        ProgressManager.getInstance().run(versionCheckTask)

        val bytes = try {
            environment.get()
        } catch (e: Exception) {
            throw RuntimeException("failed to download mirrord version $version", e)
        }

        Files.createDirectories(destination.parent)
        Files.write(destination, bytes)
        destination.toFile().setExecutable(true)
    }

    /**
     * Fetches mirrord binary.
     * Downloads and stores it in the plugin directory if necessary (local version is missing or outdated).
     *
     * @return the path to the binary
     *
     * @throws RuntimeException
     */
    fun getBinary(product: String, wslDistribution: WSLDistribution?): String {
        val timeout = if (this.getLocalBinary(null, wslDistribution) == null) 10L else 1L
        val latestVersion = this.getLatestSupportedVersion(product, Duration.ofSeconds(timeout))
        this.getLocalBinary(latestVersion, wslDistribution)?.let {
            return it
        }

        val destinationPath = MirrordPathManager.getPath(CLI_BINARY, true)
        this.downloadBinary(destinationPath, latestVersion)

        return destinationPath.toString()
    }
}

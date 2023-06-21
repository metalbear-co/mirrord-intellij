package com.metalbear.mirrord

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.system.CpuArch
import java.nio.charset.Charset
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.URLEncoder
import java.nio.file.Files
import java.time.Duration

/**
 * For dynamically fetching and storing mirrord binary.
 */
object MirrordBinaryManager {
    private const val cliBinary = "mirrord"
    private const val versionEndpoint = "https://version.mirrord.dev/v1/version"
    private const val downloadEndpoint = "https://github.com/metalbear-co/mirrord/releases/download"

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
     * @return path to the local installation of mirrord
     */
    private fun getLocalBinary(requiredVersion: String?): String? {
        try {
            val child = Runtime.getRuntime().exec(arrayOf("which", "mirrord"))
            val result = child.waitFor()
            if (result != 0) {
                throw RuntimeException("`which` failed with code $result")
            }
            val foundInPath = child.inputReader().readLine().trim()

            if (requiredVersion == null || requiredVersion == this.getVersion(foundInPath)) {
                return foundInPath
            }
        } catch (e: Exception) {
            MirrordLogger.logger.debug("failed to find mirrord in path", e)
        }

        try {
            MirrordPathManager.getBinary(this.cliBinary, true)?.let {
                if (requiredVersion == null || requiredVersion == this.getVersion(it)) {
                    return it
                }
            }
        } catch (e: Exception) {
            MirrordLogger.logger.debug("failed to find mirrord in plugin storage", e)
        }

        return null
    }

    private fun getLatestSupportedVersion(product: String, timeout: Duration, project: Project): String {
        val environment = CompletableFuture<String>()
        val versionCheckTask = object : Task.Backgroundable(project, "mirrord", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "mirrord is checking the latest supported version..."

                val version = if (System.getenv("CI_BUILD_PLUGIN") == "true") {
                    "test"
                } else {
                    MirrordVersionCheck.version ?: "unknown"
                }

                val url = StringBuilder(versionEndpoint)
                        .append("?source=3")
                        .append("&version=")
                        .append(URLEncoder.encode(version, Charset.defaultCharset()))
                        .append("&platform=")
                        .append(URLEncoder.encode(SystemInfo.getOsName(), Charset.defaultCharset()))
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
        }

        ProgressManager.getInstance().run(versionCheckTask)

        return try {
            environment.get(timeout.seconds, TimeUnit.SECONDS)
        } catch (e: Exception) {
            throw RuntimeException("failed to check the latest supported version of mirrord", e)
        }
    }

    private fun downloadBinary(destination: Path, version: String, project: Project) {
        val url = if (SystemInfo.isMac) {
            "$downloadEndpoint/$version/mirrord_mac_universal"
        } else if (SystemInfo.isLinux || SystemInfo.isWindows) {
            if (CpuArch.isArm64()) {
                "$downloadEndpoint/$version/mirrord_linux_aarch64"
            } else if (CpuArch.isIntel64()) {
                "$downloadEndpoint/$version/mirrord_linux_x86_64"
            } else {
                throw RuntimeException("Unsupported architecture: " + CpuArch.CURRENT.name)
            }
        } else {
            throw RuntimeException("Unsupported platform: " + SystemInfo.getOsName())
        }

        val environment = CompletableFuture<ByteArray>()
        val versionCheckTask = object : Task.Backgroundable(project, "mirrord", true) {
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
     * @return  the path to the binary
     *
     * @throws  RuntimeException
     */
    fun getBinary(project: Project, product: String): String {
        val timeout = if (this.getLocalBinary(null) == null) 10L else 1L
        val latestVersion = this.getLatestSupportedVersion(product, Duration.ofSeconds(timeout), project)
        this.getLocalBinary(latestVersion)?.let {
            return it
        }

        val destinationPath = MirrordPathManager.getPath(this.cliBinary, true)
        this.downloadBinary(destinationPath, latestVersion, project)

        return destinationPath.toString()
    }
}

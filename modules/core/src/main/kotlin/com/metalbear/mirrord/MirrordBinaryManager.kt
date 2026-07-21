package com.metalbear.mirrord

import com.github.zafarkhaja.semver.Version
import com.intellij.execution.wsl.WSLDistribution
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
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
 * Minimum mirrord binary version required for Windows-native execution
 * (pitm + attach). Added in https://github.com/metalbear-co/mirrord/pull/4191.
 */
private const val MIN_WINDOWS_NATIVE_VERSION = "3.204.0"

/**
 * For dynamically fetching and storing mirrord binary.
 */
@Service(Service.Level.APP)
class MirrordBinaryManager {
    @Volatile
    private var latestSupportedVersion: String? = null

    @Volatile
    private var downloadVersion: String? = null

    companion object {
        /** Cross-platform `which`/`where` lookup. Returns the first match or null. */
        fun which(binary: String): String? {
            return try {
                val cmd = if (SystemInfo.isWindows) arrayOf("where", "$binary.exe") else arrayOf("which", binary)
                val child = Runtime.getRuntime().exec(cmd)
                val result = child.waitFor()
                if (result != 0) return null
                child
                    .inputReader()
                    .readLine()
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            }
        }

        @Suppress("DEPRECATION")
        internal fun wslPath(
            wslDistribution: WSLDistribution,
            path: String,
        ): String = wslDistribution.getWslPath(path) ?: path
    }

    fun getCliPath(
        product: String,
        wslDistribution: WSLDistribution?,
        project: Project,
    ): String = getBinary(product, wslDistribution, project)

    /**
     * Schedules the update task at project startup.
     */
    class DownloadInitializer : ProjectActivity {
        override suspend fun execute(project: Project) {
            UpdateTask(project, null, null, false).queue()
        }
    }

    /**
     * Verifies the mirrord binary supports Windows-native execution
     * (>= [MIN_WINDOWS_NATIVE_VERSION]) at project startup on Windows hosts.
     * Triggers an auto-update if the binary is missing or outdated; errors if
     * the binary is still insufficient afterwards. No-op on non-Windows.
     */
    class WindowsNativeSupportInitializer : ProjectActivity {
        override suspend fun execute(project: Project) {
            if (!SystemInfo.isWindows) return
            WindowsNativeSupportCheckTask(project).queue()
        }
    }

    private class WindowsNativeSupportCheckTask(
        project: Project,
    ) : Task.Backgroundable(project, "mirrord: Windows-native support check", true),
        DumbAware {
        override fun run(indicator: ProgressIndicator) {
            service<MirrordBinaryManager>().checkWindowsNativeSupport(project, indicator)
        }
    }

    /**
     * Async wrapper around [runUpdate]. Retained so `DownloadInitializer` can
     * fire-and-forget at project startup; per-call refreshes from `getBinary`
     * call [runUpdate] directly so resolution sees settled state.
     */
    private class UpdateTask(
        private val project: Project,
        private val product: String?,
        private val wslDistribution: WSLDistribution?,
        private val checkInPath: Boolean,
    ) : Task.Backgroundable(project, "mirrord", true),
        DumbAware {
        companion object State {
            /**
             * Only one download may be happening at the same time.
             */
            val downloadInProgress = AtomicBoolean(false)
        }

        override fun run(indicator: ProgressIndicator) {
            service<MirrordBinaryManager>()
                .runUpdate(project, product, wslDistribution, checkInPath, indicator)
        }
    }

    /**
     * Refreshes [latestSupportedVersion] and downloads the binary into plugin
     * storage if no local match is available. Notifications (success / format
     * error / failure) fire from inside, so both the async [UpdateTask] caller
     * and the synchronous [getBinary] caller surface the same UX.
     */
    private fun runUpdate(
        project: Project,
        product: String?,
        wslDistribution: WSLDistribution?,
        checkInPath: Boolean,
        indicator: ProgressIndicator,
    ) {
        indicator.isIndeterminate = false

        val autoUpdate = MirrordSettingsState.instance.mirrordState.autoUpdate
        val userSelectedMirrordVersion = MirrordSettingsState.instance.mirrordState.mirrordVersion

        try {
            latestSupportedVersion = fetchLatestSupportedVersion(product, indicator)
        } catch (e: Throwable) {
            MirrordLogger.logger.debug("binary update: latest-version fetch failed", e)
            return
        }

        val version =
            when {
                // auto update -> false -> use mirrordVersion if it's not empty
                !autoUpdate && (userSelectedMirrordVersion.isNotEmpty()) -> {
                    try {
                        Version.parse(userSelectedMirrordVersion)
                    } catch (e: Exception) {
                        project
                            .service<MirrordProjectService>()
                            .notifier
                            .notification("mirrord version format is invalid!", NotificationType.WARNING)
                            .fire()
                        return
                    }
                    userSelectedMirrordVersion
                }
                // auto update -> false -> mirrordVersion is empty -> needs check in the path
                // if not in path -> fetch latest version
                !autoUpdate && userSelectedMirrordVersion.isEmpty() -> null

                // auto update -> true -> fetch latest version
                else -> latestSupportedVersion
            }

        val local =
            if (checkInPath) {
                getLocalBinary(version, wslDistribution)
            } else {
                findBinaryInStorage(version, wslDistribution)
            }

        if (local != null) {
            return
        }

        downloadVersion = version
            // auto update -> false -> mirrordVersion is empty -> no cli found locally -> latest version
            ?: latestSupportedVersion

        if (UpdateTask.downloadInProgress.compareAndExchange(false, true)) {
            return
        }

        try {
            updateBinary(indicator, wslDistribution)
            val downloaded = downloadVersion
            if (downloaded != null) {
                project
                    .service<MirrordProjectService>()
                    .notifier
                    .notifySimple(
                        "downloaded mirrord binary version $downloaded",
                        NotificationType.INFORMATION,
                    )
            }
        } catch (e: Throwable) {
            MirrordLogger.logger.debug("binary download failed", e)
            project
                .service<MirrordProjectService>()
                .notifier
                .notifyRichError(
                    "failed to update the mirrord binary: ${e.message ?: e.toString()}",
                )
        } finally {
            UpdateTask.downloadInProgress.set(false)
        }
    }

    private fun fetchLatestSupportedVersion(
        product: String?,
        indicator: ProgressIndicator,
    ): String {
        val pluginVersion =
            if (
                System.getenv("CI_BUILD_PLUGIN") == "true" ||
                System.getenv("PLUGIN_TESTING_ENVIRONMENT") == "true"
            ) {
                "test"
            } else {
                VERSION ?: "unknown"
            }

        indicator.text = "mirrord is checking the latest supported binary version..."

        val url =
            StringBuilder(VERSION_ENDPOINT)
                .append("?source=3")
                .append("&version=")
                .append(URLEncoder.encode(pluginVersion, Charset.defaultCharset()))
                .append("&platform=")
                .append(URLEncoder.encode(SystemInfo.OS_NAME, Charset.defaultCharset()))
                .toString()

        val client = HttpClient.newHttpClient()
        val builder =
            HttpRequest
                .newBuilder(URI(url))
                .timeout(Duration.ofSeconds(10L))
                .GET()

        product?.let { builder.header("user-agent", it) }

        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())

        return response.body()
    }

    private fun updateBinary(
        indicator: ProgressIndicator,
        wslDistribution: WSLDistribution? = null,
    ) {
        val version = downloadVersion ?: return

        val url =
            if (SystemInfo.isMac) {
                "$DOWNLOAD_ENDPOINT/$version/mirrord_mac_universal"
            } else if (isWinNative(wslDistribution)) {
                if (CpuArch.isIntel64()) {
                    "$DOWNLOAD_ENDPOINT/$version/mirrord.exe"
                } else {
                    throw RuntimeException("Unsupported architecture: ${CpuArch.CURRENT.name}")
                }
            } else if (SystemInfo.isLinux || SystemInfo.isWindows) {
                // Native Linux, or Windows host targeting WSL — both use the Linux
                // binary. WSL inherits host arch, so CpuArch is the right proxy.
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

        val bytes = ByteArray(size)
        // .use { } guarantees the stream closes (and the underlying connection is
        // released back to the pool) even if the user cancels mid-download or a
        // socket error fires from the read loop.
        connection.getInputStream().use { stream ->
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
        }

        val destination = MirrordPathManager.getPath(CLI_BINARY, true, wslDistribution)
        Files.createDirectories(destination.parent)

        val tmpDestination = destination.resolveSibling(destination.name + UUID.randomUUID().toString())

        Files.write(tmpDestination, bytes)
        destination.toFile().setExecutable(true)
        Files.move(tmpDestination, destination, StandardCopyOption.REPLACE_EXISTING)
    }

    private class MirrordBinary(
        val command: String,
        wslDistribution: WSLDistribution?,
    ) {
        val version: String

        init {
            version =
                if (wslDistribution != null) {
                    val command = wslPath(wslDistribution, command)
                    val output = wslDistribution.executeOnWsl(5000, command, "--version")
                    output.stdout.split(' ')[1].trim()
                } else {
                    val child = Runtime.getRuntime().exec(arrayOf(command, "--version"))
                    val result = child.waitFor()
                    if (result != 0) {
                        MirrordLogger.logger.debug("`mirrord --version` failed with code $result")
                        throw RuntimeException("failed to get mirrord version")
                    }

                    child
                        .inputReader()
                        .readLine()
                        .split(' ')[1]
                        .trim()
                }
        }
    }

    /**
     * @return executable found with `which mirrord`
     */
    private fun findBinaryInPath(
        requiredVersion: String?,
        wslDistribution: WSLDistribution?,
    ): MirrordBinary? {
        try {
            val output =
                if (wslDistribution == null) {
                    which("mirrord") ?: throw RuntimeException("mirrord not found in PATH")
                } else {
                    val output = wslDistribution.executeOnWsl(5000, "which", "mirrord")
                    if (output.exitCode != 0) {
                        throw RuntimeException("`which` failed with code ${output.exitCode}")
                    }
                    output.stdoutLines.first().trim()
                }

            val binary = MirrordBinary(output, wslDistribution)
            val isRequiredVersion =
                try {
                    // for release CI, the tag can be greater than the latest release
                    if (System.getenv("CI_BUILD_PLUGIN") == "true") {
                        Version.parse(binary.version).isHigherThanOrEquivalentTo(Version.parse(requiredVersion))
                    } else {
                        Version.parse(binary.version).equals(Version.parse(requiredVersion))
                    }
                } catch (e: Exception) {
                    MirrordLogger.logger.debug("failed to parse version", e)
                    false
                }
            if (requiredVersion == null || isRequiredVersion) {
                return binary
            }
        } catch (e: Exception) {
            MirrordLogger.logger.debug("failed to find mirrord in path", e)
        }
        return null
    }

    /**
     * @return executable found in plugin storage
     */
    private fun findBinaryInStorage(
        requiredVersion: String?,
        wslDistribution: WSLDistribution?,
    ): MirrordBinary? {
        try {
            MirrordPathManager.getBinary(CLI_BINARY, true, wslDistribution)?.let {
                val binary = MirrordBinary(it, wslDistribution)
                val isRequiredVersion =
                    try {
                        Version.parse(binary.version).equals(Version.parse(requiredVersion))
                    } catch (e: Exception) {
                        MirrordLogger.logger.debug("failed to parse version", e)
                        false
                    }
                if (requiredVersion == null || isRequiredVersion) {
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
    private fun getLocalBinary(
        requiredVersion: String?,
        wslDistribution: WSLDistribution?,
    ): MirrordBinary? = findBinaryInPath(requiredVersion, wslDistribution) ?: findBinaryInStorage(requiredVersion, wslDistribution)

    /**
     * Verifies the local mirrord binary is at least [MIN_WINDOWS_NATIVE_VERSION].
     * On mismatch, force-downloads the latest supported version, then re-checks.
     * If still insufficient, surfaces a rich error — Windows-native run/debug will
     * fail until the binary is upgraded; WSL configurations are unaffected.
     *
     * Skips entirely on non-Windows hosts. On non-x64 Windows, surfaces the
     * architecture-specific error early (same interest-gauge message as
     * [updateBinary]) and returns.
     */
    fun checkWindowsNativeSupport(
        project: Project,
        indicator: ProgressIndicator,
    ) {
        if (!SystemInfo.isWindows) return

        if (!CpuArch.isIntel64()) {
            MirrordWindowsUnsupportedDialog.showArchUnsupportedOnce(CpuArch.CURRENT.name)
            return
        }

        val required =
            try {
                Version.parse(MIN_WINDOWS_NATIVE_VERSION)
            } catch (e: Exception) {
                MirrordLogger.logger.error(
                    "checkWindowsNativeSupport: failed to parse the hardcoded minimum Windows-native mirrord version " +
                        "(\"$MIN_WINDOWS_NATIVE_VERSION\"). This is a bug in the plugin — please report it.",
                    e,
                )
                return
            }

        fun resolveLocal(): MirrordBinary? =
            try {
                getLocalBinary(null, null)
            } catch (e: Exception) {
                MirrordLogger.logger.debug("checkWindowsNativeSupport: local lookup failed", e)
                null
            }

        fun Version?.satisfies(): Boolean = this != null && this.isHigherThanOrEquivalentTo(required)

        fun parse(raw: String?): Version? =
            raw?.let {
                try {
                    Version.parse(it)
                } catch (_: Exception) {
                    null
                }
            }

        val currentBinary = resolveLocal()
        val current = parse(currentBinary?.version)
        if (current.satisfies()) {
            MirrordLogger.logger.info(
                "checkWindowsNativeSupport: binary $current satisfies >= $MIN_WINDOWS_NATIVE_VERSION",
            )
            return
        }

        // Only force-update if the user has auto-update enabled. If they've pinned
        // a specific version or disabled auto-update, respect that and notify instead.
        val autoUpdate = MirrordSettingsState.instance.mirrordState.autoUpdate
        if (!autoUpdate) {
            MirrordLogger.logger.warn(
                "checkWindowsNativeSupport: local binary ${current ?: "none"} < $MIN_WINDOWS_NATIVE_VERSION, " +
                    "auto-update is OFF in settings; surfacing error without download",
            )
            notifyWindowsNativeUnsupported(currentBinary)
            return
        }

        MirrordLogger.logger.warn(
            "checkWindowsNativeSupport: local binary ${current ?: "none"} < $MIN_WINDOWS_NATIVE_VERSION, " +
                "triggering auto-update",
        )

        // Take the download lock so we don't race a concurrent DownloadInitializer.
        // If we can't acquire, another auto-update is already fetching latest —
        // which by construction satisfies MIN_WINDOWS_NATIVE_VERSION — so we bow
        // out and let that task's own error handling surface any failure.
        val acquired = !UpdateTask.downloadInProgress.compareAndExchange(false, true)
        if (!acquired) {
            MirrordLogger.logger.info(
                "checkWindowsNativeSupport: another download is in progress (auto-update); " +
                    "deferring to it and skipping the Windows-native recheck",
            )
            return
        }

        try {
            indicator.text = "mirrord: downloading binary for Windows-native support..."
            val latest = fetchLatestSupportedVersion(null, indicator)
            latestSupportedVersion = latest
            downloadVersion = latest
            updateBinary(indicator)
        } catch (e: Exception) {
            MirrordLogger.logger.warn("checkWindowsNativeSupport: auto-update failed: ${e.message}", e)
        } finally {
            UpdateTask.downloadInProgress.set(false)
        }

        val afterBinary = resolveLocal()
        val after = parse(afterBinary?.version)
        if (after.satisfies()) {
            MirrordLogger.logger.info(
                "checkWindowsNativeSupport: after update, binary $after satisfies >= $MIN_WINDOWS_NATIVE_VERSION",
            )
            return
        }

        notifyWindowsNativeUnsupported(afterBinary)
    }

    private fun notifyWindowsNativeUnsupported(binary: MirrordBinary?) {
        val actual = binary?.version ?: "none"
        MirrordWindowsUnsupportedDialog.showVersionUnsupportedOnce(
            MIN_WINDOWS_NATIVE_VERSION,
            actual,
            binary?.command,
        )
    }

    /**
     * Asserts the resolved binary supports Windows-native execution. No-op on
     * non-Windows-native targets (Linux, macOS, Windows host targeting WSL).
     * On Windows-native, throws if the version is below [MIN_WINDOWS_NATIVE_VERSION]
     * or unparseable. The error body uses the same wording as the proactive
     * startup dialog so users see the same warning + path on both surfaces.
     */
    private fun enforceWindowsNativeMin(
        binary: MirrordBinary,
        wslDistribution: WSLDistribution?,
    ) {
        if (!isWinNative(wslDistribution)) return
        val parsed =
            try {
                Version.parse(binary.version)
            } catch (_: Exception) {
                null
            }
        val required = Version.parse(MIN_WINDOWS_NATIVE_VERSION)
        if (parsed != null && parsed.isHigherThanOrEquivalentTo(required)) return

        val found = parsed?.toString() ?: binary.version.ifBlank { "none" }
        val body =
            MirrordWindowsUnsupportedDialog.buildVersionUnsupportedBody(
                MIN_WINDOWS_NATIVE_VERSION,
                found,
                binary.command,
            )
        throw MirrordError(
            body,
            "Update mirrord to version >= $MIN_WINDOWS_NATIVE_VERSION (enable auto-update or pin a newer version).",
        )
    }

    /**
     * Resolves the mirrord binary to use for this invocation. Settles binary
     * state synchronously up-front (`runUpdate`) so concurrent `getBinary`
     * calls in one execution see the same state. Custom `mirrordBinaryPath`
     * setting takes precedence; otherwise resolves against the wanted version
     * derived from `autoUpdate` / `mirrordVersion`.
     *
     * @throws MirrordError if no local binary could be resolved
     * @return the path to the binary
     */
    fun getBinary(
        product: String,
        wslDistribution: WSLDistribution?,
        project: Project,
    ): String {
        val customPath =
            MirrordSettingsState.instance.mirrordState.mirrordBinaryPath
                .trim()
        if (customPath.isNotEmpty()) {
            validateCustomBinary(customPath, wslDistribution, project)?.let {
                enforceWindowsNativeMin(it, wslDistribution)
                return it.command
            }
        }

        val indicator = ProgressManager.getInstance().progressIndicator ?: EmptyProgressIndicator()
        runUpdate(project, product, wslDistribution, true, indicator)

        val autoUpdate = MirrordSettingsState.instance.mirrordState.autoUpdate
        val userVersion = MirrordSettingsState.instance.mirrordState.mirrordVersion
        val wantedVersion =
            when {
                autoUpdate -> latestSupportedVersion
                userVersion.isNotEmpty() -> userVersion
                else -> null
            }

        getLocalBinary(wantedVersion, wslDistribution)?.let {
            enforceWindowsNativeMin(it, wslDistribution)
            return it.command
        }

        throw MirrordError(
            "no local installation of mirrord binary was found",
            "mirrord binary will be downloaded in the background",
        )
    }

    private fun validateCustomBinary(
        path: String,
        wslDistribution: WSLDistribution?,
        project: Project,
    ): MirrordBinary? =
        try {
            MirrordBinary(path, wslDistribution)
        } catch (e: Exception) {
            MirrordLogger.logger.debug("custom mirrord binary path is invalid: $path", e)
            project
                .service<MirrordProjectService>()
                .notifier
                .notification(
                    "custom mirrord binary path is invalid: $path",
                    NotificationType.WARNING,
                ).withDontShowAgain(MirrordSettingsState.NotificationId.MIRRORD_BINARY_PATH_INVALID)
                .fire()
            null
        }
}

package com.metalbear.mirrord

import com.github.zafarkhaja.semver.Version
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
import com.metalbear.mirrord.bifrost.MirrordEnvironment
import com.metalbear.mirrord.bifrost.MirrordEnvironments
import com.metalbear.mirrord.bifrost.MirrordLaunchContext
import com.metalbear.mirrord.bifrost.MirrordTargetArch
import com.metalbear.mirrord.bifrost.MirrordTargetPlatform
import com.metalbear.mirrord.bifrost.TargetPath
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
 * (pitm + attach). Set to the first release with the hardened Windows Java
 * Debug runtime (https://github.com/metalbear-co/mirrord/pull/4661) that
 * Gradle Run and Debug rely on.
 */
private const val MIN_WINDOWS_NATIVE_VERSION = "3.245.0"

/** How long to wait for a short probe such as `mirrord --version` or `which mirrord`. */
private const val PROBE_TIMEOUT_MILLIS = 5000L

/**
 * The release asset for a given target platform.
 *
 * Pure, so every combination is unit-tested — including the ones that used to be unreachable.
 * The old form asked `SystemInfo` and `CpuArch`, i.e. the IDE host, so a macOS IDE could never
 * have picked a Linux asset even when the project ran in a Linux container.
 */
internal fun mirrordDownloadUrl(version: String, platform: MirrordTargetPlatform): String = when {
    // Darwin ships one universal binary covering both architectures.
    platform.isMac -> "$DOWNLOAD_ENDPOINT/$version/mirrord_mac_universal"

    platform.isWindows -> when (platform.arch) {
        MirrordTargetArch.X86_64 -> "$DOWNLOAD_ENDPOINT/$version/mirrord.exe"
        MirrordTargetArch.ARM64 -> throw MirrordError(
            "mirrord has no Windows build for ${platform.archDirectoryName}.",
            "Windows support currently requires an x86-64 target."
        )
    }

    else -> when (platform.arch) {
        MirrordTargetArch.ARM64 -> "$DOWNLOAD_ENDPOINT/$version/mirrord_linux_aarch64"
        MirrordTargetArch.X86_64 -> "$DOWNLOAD_ENDPOINT/$version/mirrord_linux_x86_64"
    }
}

/**
 * For dynamically fetching and storing mirrord binary.
 */
@Service(Service.Level.APP)
class MirrordBinaryManager {
    @Volatile
    private var latestSupportedVersion: String? = null

    @Volatile
    private var downloadVersion: String? = null

    fun getCliPath(product: String, environment: MirrordEnvironment, project: Project): TargetPath {
        return getBinary(product, environment, project)
    }

    /**
     * Schedules the update task at project startup.
     */
    class DownloadInitializer : ProjectActivity {
        override suspend fun execute(project: Project) {
            UpdateTask(project, null, MirrordEnvironments.resolve(MirrordLaunchContext(project)), false).queue()
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

    private class WindowsNativeSupportCheckTask(project: Project) :
        Task.Backgroundable(project, "mirrord: Windows-native support check", true), DumbAware {
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
        private val environment: MirrordEnvironment,
        private val checkInPath: Boolean
    ) : Task.Backgroundable(project, "mirrord", true), DumbAware {
        companion object State {
            /**
             * Only one download may be happening at the same time.
             */
            val downloadInProgress = AtomicBoolean(false)
        }

        override fun run(indicator: ProgressIndicator) {
            service<MirrordBinaryManager>()
                .runUpdate(project, product, environment, checkInPath, indicator)
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
        environment: MirrordEnvironment,
        checkInPath: Boolean,
        indicator: ProgressIndicator
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

        val version = when {
            // auto update -> false -> use mirrordVersion if it's not empty
            !autoUpdate && (userSelectedMirrordVersion.isNotEmpty()) -> {
                try {
                    Version.valueOf(userSelectedMirrordVersion)
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

        val local = if (checkInPath) {
            getLocalBinary(version, environment)
        } else {
            findBinaryInStorage(version, environment)
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
            updateBinary(indicator, environment.platform())
            val downloaded = downloadVersion
            if (downloaded != null) {
                project
                    .service<MirrordProjectService>()
                    .notifier
                    .notifySimple(
                        "downloaded mirrord binary version $downloaded",
                        NotificationType.INFORMATION
                    )
            }
        } catch (e: Throwable) {
            MirrordLogger.logger.debug("binary download failed", e)
            project
                .service<MirrordProjectService>()
                .notifier
                .notifyRichError(
                    "failed to update the mirrord binary: ${e.message ?: e.toString()}"
                )
        } finally {
            UpdateTask.downloadInProgress.set(false)
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

    private fun updateBinary(indicator: ProgressIndicator, platform: MirrordTargetPlatform) {
        val version = downloadVersion ?: return

        val url = mirrordDownloadUrl(version, platform)

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

        val destination = MirrordPathManager.getPath(CLI_BINARY, true, platform).path
        Files.createDirectories(destination.parent)

        val tmpDestination = destination.resolveSibling(destination.name + UUID.randomUUID().toString())

        Files.write(tmpDestination, bytes)
        // Set the bit on the file we are about to move, not on the one it replaces. The old
        // order marked the *previous* binary executable (or nothing at all, on a first install)
        // and only worked because MirrordPathManager repaired it lazily on the next read. That
        // repair is host-side, so it cannot help a copy staged into a container.
        tmpDestination.toFile().setExecutable(true)
        Files.move(tmpDestination, destination, StandardCopyOption.REPLACE_EXISTING)
    }

    private class MirrordBinary(val command: TargetPath, environment: MirrordEnvironment) {
        val version: String

        init {
            val output = environment.probe(command, listOf("--version"), PROBE_TIMEOUT_MILLIS)
            // Parse leniently and report the exit code only when the output is unusable. The old
            // WSL path ignored the exit code entirely, so failing on it here would have been a
            // behaviour change for WSL rather than a fix.
            version = output.stdout.trim().split(' ').getOrNull(1)?.trim()
                ?: run {
                    MirrordLogger.logger.debug(
                        "`mirrord --version` gave exit=${output.exitCode} stdout='${output.stdout}' stderr='${output.stderr}'"
                    )
                    throw RuntimeException("failed to get mirrord version")
                }
        }
    }

    /**
     * @return executable found on the target's `PATH`
     */
    private fun findBinaryInPath(requiredVersion: String?, environment: MirrordEnvironment): MirrordBinary? {
        try {
            val windows = environment.platform().isWindows
            val locator = TargetPath(if (windows) "where" else "which")
            val wanted = if (windows) "$CLI_BINARY.exe" else CLI_BINARY

            val output = environment.probe(locator, listOf(wanted), PROBE_TIMEOUT_MILLIS)
            if (output.exitCode != 0) {
                throw RuntimeException("`${locator.value}` failed with code ${output.exitCode}")
            }
            val found = output.stdout.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
                ?: throw RuntimeException("mirrord not found in PATH")

            val binary = MirrordBinary(TargetPath(found), environment)
            val isRequiredVersion = try {
                // for release CI, the tag can be greater than the latest release
                if (System.getenv("CI_BUILD_PLUGIN") == "true") {
                    Version.valueOf(binary.version).greaterThanOrEqualTo(Version.valueOf(requiredVersion))
                } else {
                    Version.valueOf(binary.version).equals(Version.valueOf(requiredVersion))
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
    private fun findBinaryInStorage(requiredVersion: String?, environment: MirrordEnvironment): MirrordBinary? {
        try {
            MirrordPathManager.getBinary(CLI_BINARY, true, environment.platform())?.let { hostPath ->
                // Plugin storage lives on the IDE host, which a container cannot see at any
                // path. `provide` copies it across if it has to, and does nothing when the
                // target can already reach it — local and WSL never pay for a transfer.
                val binary = MirrordBinary(environment.provide(hostPath, CLI_BINARY), environment)
                val isRequiredVersion = try {
                    Version.valueOf(binary.version).equals(Version.valueOf(requiredVersion))
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
    private fun getLocalBinary(requiredVersion: String?, environment: MirrordEnvironment): MirrordBinary? {
        return findBinaryInPath(requiredVersion, environment) ?: findBinaryInStorage(requiredVersion, environment)
    }

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
    fun checkWindowsNativeSupport(project: Project, indicator: ProgressIndicator) {
        if (!SystemInfo.isWindows) return

        if (!CpuArch.isIntel64()) {
            MirrordWindowsUnsupportedDialog.showArchUnsupportedOnce(CpuArch.CURRENT.name)
            return
        }

        val required = try {
            Version.valueOf(MIN_WINDOWS_NATIVE_VERSION)
        } catch (e: Exception) {
            MirrordLogger.logger.error(
                "checkWindowsNativeSupport: failed to parse the hardcoded minimum Windows-native mirrord version " +
                    "(\"$MIN_WINDOWS_NATIVE_VERSION\"). This is a bug in the plugin — please report it.",
                e
            )
            return
        }

        // Resolve against the project's environment rather than assuming the host, and
        // honour a custom binary path the way `getBinary` does — otherwise a user who pointed
        // the plugin at their own build is still told it is unsupported, and can even be
        // force-fed a download of the managed one.
        val environment = MirrordEnvironments.resolve(MirrordLaunchContext(project))

        fun resolveLocal(): MirrordBinary? = try {
            val customPath = MirrordSettingsState.instance.mirrordState.mirrordBinaryPath.trim()
            customPath.takeIf { it.isNotEmpty() }
                ?.let { validateCustomBinary(it, environment, project) }
                ?: getLocalBinary(null, environment)
        } catch (e: Exception) {
            MirrordLogger.logger.debug("checkWindowsNativeSupport: local lookup failed", e)
            null
        }

        fun Version?.satisfies(): Boolean = this != null && this.greaterThanOrEqualTo(required)
        fun parse(raw: String?): Version? = raw?.let {
            try {
                Version.valueOf(it)
            } catch (_: Exception) {
                null
            }
        }

        val currentBinary = resolveLocal()
        val current = parse(currentBinary?.version)
        if (current.satisfies()) {
            MirrordLogger.logger.info(
                "checkWindowsNativeSupport: binary $current satisfies >= $MIN_WINDOWS_NATIVE_VERSION"
            )
            return
        }

        // Only force-update if the user has auto-update enabled. If they've pinned
        // a specific version or disabled auto-update, respect that and notify instead.
        val autoUpdate = MirrordSettingsState.instance.mirrordState.autoUpdate
        if (!autoUpdate) {
            MirrordLogger.logger.warn(
                "checkWindowsNativeSupport: local binary ${current ?: "none"} < $MIN_WINDOWS_NATIVE_VERSION, " +
                    "auto-update is OFF in settings; surfacing error without download"
            )
            notifyWindowsNativeUnsupported(currentBinary)
            return
        }

        MirrordLogger.logger.warn(
            "checkWindowsNativeSupport: local binary ${current ?: "none"} < $MIN_WINDOWS_NATIVE_VERSION, " +
                "triggering auto-update"
        )

        // Take the download lock so we don't race a concurrent DownloadInitializer.
        // If we can't acquire, another auto-update is already fetching latest —
        // which by construction satisfies MIN_WINDOWS_NATIVE_VERSION — so we bow
        // out and let that task's own error handling surface any failure.
        val acquired = !UpdateTask.downloadInProgress.compareAndExchange(false, true)
        if (!acquired) {
            MirrordLogger.logger.info(
                "checkWindowsNativeSupport: another download is in progress (auto-update); " +
                    "deferring to it and skipping the Windows-native recheck"
            )
            return
        }

        try {
            indicator.text = "mirrord: downloading binary for Windows-native support..."
            val latest = fetchLatestSupportedVersion(null, indicator)
            latestSupportedVersion = latest
            downloadVersion = latest
            updateBinary(indicator, environment.platform())
        } catch (e: Exception) {
            MirrordLogger.logger.warn("checkWindowsNativeSupport: auto-update failed: ${e.message}", e)
        } finally {
            UpdateTask.downloadInProgress.set(false)
        }

        val afterBinary = resolveLocal()
        val after = parse(afterBinary?.version)
        if (after.satisfies()) {
            MirrordLogger.logger.info(
                "checkWindowsNativeSupport: after update, binary $after satisfies >= $MIN_WINDOWS_NATIVE_VERSION"
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
            binary?.command?.value
        )
    }

    /**
     * Asserts the resolved binary supports Windows-native execution. No-op on
     * non-Windows-native targets (Linux, macOS, Windows host targeting WSL).
     * On Windows-native, throws if the version is below [MIN_WINDOWS_NATIVE_VERSION]
     * or unparseable. The error body uses the same wording as the proactive
     * startup dialog so users see the same warning + path on both surfaces.
     */
    private fun enforceWindowsNativeMin(binary: MirrordBinary, platform: MirrordTargetPlatform) {
        if (!isWinNative(platform)) return
        val parsed = try {
            Version.valueOf(binary.version)
        } catch (_: Exception) {
            null
        }
        val required = Version.valueOf(MIN_WINDOWS_NATIVE_VERSION)
        if (parsed != null && parsed.greaterThanOrEqualTo(required)) return

        val found = parsed?.toString() ?: binary.version.ifBlank { "none" }
        val body = MirrordWindowsUnsupportedDialog.buildVersionUnsupportedBody(
            MIN_WINDOWS_NATIVE_VERSION,
            found,
            binary.command.value
        )
        throw MirrordError(
            body,
            "Update mirrord to version >= $MIN_WINDOWS_NATIVE_VERSION (enable auto-update or pin a newer version)."
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
    fun getBinary(product: String, environment: MirrordEnvironment, project: Project): TargetPath {
        val customPath = MirrordSettingsState.instance.mirrordState.mirrordBinaryPath.trim()
        if (customPath.isNotEmpty()) {
            validateCustomBinary(customPath, environment, project)?.let {
                enforceWindowsNativeMin(it, environment.platform())
                return it.command
            }
        }

        val indicator = ProgressManager.getInstance().progressIndicator ?: EmptyProgressIndicator()
        runUpdate(project, product, environment, true, indicator)

        val autoUpdate = MirrordSettingsState.instance.mirrordState.autoUpdate
        val userVersion = MirrordSettingsState.instance.mirrordState.mirrordVersion
        val wantedVersion = when {
            autoUpdate -> latestSupportedVersion
            userVersion.isNotEmpty() -> userVersion
            else -> null
        }

        getLocalBinary(wantedVersion, environment)?.let {
            enforceWindowsNativeMin(it, environment.platform())
            return it.command
        }

        throw MirrordError(
            "no local installation of mirrord binary was found",
            "mirrord binary will be downloaded in the background"
        )
    }

    private fun validateCustomBinary(
        path: String,
        environment: MirrordEnvironment,
        project: Project
    ): MirrordBinary? {
        return try {
            // Already a target-side path: the user typed something meaningful where mirrord
            // runs, so it must not be translated. The old code relied on getWslPath returning
            // null for an already-Linux path; this makes that reliance explicit.
            MirrordBinary(TargetPath(path), environment)
        } catch (e: Exception) {
            MirrordLogger.logger.debug("custom mirrord binary path is invalid: $path", e)
            project
                .service<MirrordProjectService>()
                .notifier
                .notification(
                    "custom mirrord binary path is invalid: $path",
                    NotificationType.WARNING
                )
                .withDontShowAgain(MirrordSettingsState.NotificationId.MIRRORD_BINARY_PATH_INVALID)
                .fire()
            null
        }
    }
}

package com.metalbear.mirrord

import com.intellij.openapi.application.PathManager
import com.metalbear.mirrord.bifrost.HostPath
import com.metalbear.mirrord.bifrost.MirrordTargetPlatform
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Where a plugin-managed binary lives on the IDE host.
 *
 * These are always [HostPath]s. The plugin directory belongs to the IDE, so under a dev
 * container nothing here is visible to the process mirrord is attaching to — use
 * [com.metalbear.mirrord.bifrost.MirrordEnvironment.provide] to get a binary across.
 */
object MirrordPathManager {
    private fun pluginDir(): Path = Paths.get(PathManager.getPluginsPath(), "mirrord")

    /**
     * The layout under the plugin directory for a binary built for [platform].
     *
     * Pure, so every combination is unit-tested. Note this asks the *target* platform, not the
     * IDE host: a macOS host running a Linux container needs `bin/linux/x86-64/mirrord`, and the
     * old `SystemInfo`-based version would have handed back a macOS build.
     */
    fun binaryRelativePath(name: String, universalOnMac: Boolean, platform: MirrordTargetPlatform): String {
        val binaryName = if (platform.isWindows) "$name.exe" else name
        return if (platform.isMac && universalOnMac) {
            // Darwin builds ship as one universal binary, so there is no architecture segment.
            "bin/${platform.osDirectoryName}/$binaryName"
        } else {
            "bin/${platform.osDirectoryName}/${platform.archDirectoryName}/$binaryName"
        }
    }

    /** Resolves the on-disk host path a binary for [platform] would occupy. */
    fun getPath(name: String, universalOnMac: Boolean, platform: MirrordTargetPlatform): HostPath =
        HostPath(pluginDir().resolve(binaryRelativePath(name, universalOnMac, platform)))

    /** As [getPath], but null unless the file exists and could be made executable. */
    fun getBinary(name: String, universalOnMac: Boolean, platform: MirrordTargetPlatform): HostPath? {
        val binaryPath = getPath(name, universalOnMac, platform).path.takeIf { Files.exists(it) } ?: return null
        return if (Files.isExecutable(binaryPath) || binaryPath.toFile().setExecutable(true)) {
            HostPath(binaryPath)
        } else {
            null
        }
    }

    /**
     * For helper binaries that genuinely run on the IDE host rather than in the target — at
     * present only Goland's `dlv`, which the IDE launches itself.
     */
    fun getHostBinary(name: String, universalOnMac: Boolean): HostPath? =
        getBinary(name, universalOnMac, MirrordTargetPlatform.ofHost())
}

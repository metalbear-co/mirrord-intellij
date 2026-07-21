package com.metalbear.mirrord

import com.intellij.execution.wsl.WSLDistribution
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.system.CpuArch
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * For accessing to binaries stored in the plugin directory.
 */
object MirrordPathManager {
    private fun pluginDir(): Path = Paths.get(PathManager.getPluginsPath(), "mirrord")

    /**
     * Resolves the on-disk path to a plugin-managed binary.
     *
     * When [wslDistribution] is non-null on a Windows host, the target is a
     * WSL distribution — a Linux binary is required, not `mirrord.exe`. WSL
     * runs are treated like native Linux (linux folder, no `.exe`) regardless
     * of host OS. Windows-native runs pass `wslDistribution = null`.
     */
    fun getPath(
        name: String,
        universalOnMac: Boolean,
        wslDistribution: WSLDistribution? = null,
    ): Path {
        val treatAsLinux = SystemInfo.isLinux || wslDistribution != null
        val os =
            when {
                treatAsLinux -> "linux"
                SystemInfo.isMac -> "macos"
                SystemInfo.isWindows -> "windows"
                else -> throw RuntimeException("Unsupported platform: " + SystemInfo.OS_NAME)
            }

        val arch =
            when {
                // WSL inherits the host architecture on Windows, so CpuArch is the
                // right proxy for both native Linux and Windows+WSL.
                CpuArch.isIntel64() -> "x86-64"
                CpuArch.isArm64() -> "arm64"
                else -> throw RuntimeException("Unsupported architecture: " + CpuArch.CURRENT.name)
            }

        val binaryName = if (isWinNative(wslDistribution)) "$name.exe" else name

        val format =
            when {
                SystemInfo.isMac && universalOnMac -> "bin/$os/$binaryName"
                else -> "bin/$os/$arch/$binaryName"
            }

        return pluginDir().resolve(format)
    }

    /**
     * Get matching binary based on platform and architecture. See [getPath]
     * for the semantics of [wslDistribution].
     */
    fun getBinary(
        name: String,
        universalOnMac: Boolean,
        wslDistribution: WSLDistribution? = null,
    ): String? {
        val binaryPath = this.getPath(name, universalOnMac, wslDistribution).takeIf { Files.exists(it) } ?: return null
        return if (Files.isExecutable(binaryPath) || binaryPath.toFile().setExecutable(true)) {
            return binaryPath.toString()
        } else {
            null
        }
    }
}

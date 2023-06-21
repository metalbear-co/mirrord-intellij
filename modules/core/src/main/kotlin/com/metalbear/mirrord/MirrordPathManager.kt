package com.metalbear.mirrord

import com.intellij.openapi.application.PathManager
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.Files
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.system.CpuArch

/**
 * For accessing to binaries stored in the plugin directory.
 */
object MirrordPathManager {
    private fun pluginDir(): Path = Paths.get(PathManager.getPluginsPath(), "mirrord")

    fun getPath(name: String, universalOnMac: Boolean): Path {
        val os = when {
            SystemInfo.isLinux -> "linux"
            SystemInfo.isMac -> "macos"
            SystemInfo.isWindows -> "linux"
            else -> throw RuntimeException("Unsupported platform: " + SystemInfo.OS_NAME)
        }

        val arch = when {
            CpuArch.isIntel64() -> "x86-64"
            CpuArch.isArm64() -> "arm64"
            else -> throw RuntimeException("Unsupported architecture: " + CpuArch.CURRENT.name)
        }

        val format = when {
            SystemInfo.isMac && universalOnMac -> "bin/$os/$name"
            else -> "bin/$os/$arch/$name"
        }

        return pluginDir().resolve(format)
    }

    /**
     * Get matching binary based on platform and architecture.
     */
    fun getBinary(name: String, universalOnMac: Boolean): String? {
        val binaryPath = this.getPath(name, universalOnMac).takeIf { Files.exists(it) } ?: return null
        return if (Files.isExecutable(binaryPath) || binaryPath.toFile().setExecutable(true)) {
            return binaryPath.toString()
        } else {
            null
        }
    }
}
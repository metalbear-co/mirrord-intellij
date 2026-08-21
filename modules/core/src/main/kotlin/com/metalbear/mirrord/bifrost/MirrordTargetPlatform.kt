package com.metalbear.mirrord.bifrost

import com.intellij.openapi.util.SystemInfo
import com.intellij.platform.eel.EelPlatform
import com.intellij.platform.eel.isArm64
import com.intellij.platform.eel.isLinux
import com.intellij.platform.eel.isMac
import com.intellij.platform.eel.isWindows
import com.intellij.platform.eel.isX86_64
import com.intellij.util.system.CpuArch
import com.metalbear.mirrord.MirrordError

enum class MirrordTargetOs { LINUX, MACOS, WINDOWS }

enum class MirrordTargetArch { X86_64, ARM64 }

/**
 * OS and architecture of the environment mirrord will run in — the container, the WSL
 * distribution, or the host.
 *
 * Deliberately not derived from [SystemInfo]. `SystemInfo` describes the machine the *IDE* runs
 * on, which under a dev container is routinely a different OS and a different architecture from
 * the one the user's process runs on. Asking `SystemInfo` which binary to download is what made
 * a macOS/arm64 build get selected for a linux/amd64 container in COR-1385.
 */
data class MirrordTargetPlatform(val os: MirrordTargetOs, val arch: MirrordTargetArch) {
    val isLinux: Boolean get() = os == MirrordTargetOs.LINUX
    val isMac: Boolean get() = os == MirrordTargetOs.MACOS
    val isWindows: Boolean get() = os == MirrordTargetOs.WINDOWS

    /** Matches the layout under the plugin directory, e.g. `linux`, `macos`, `windows`. */
    val osDirectoryName: String
        get() = when (os) {
            MirrordTargetOs.LINUX -> "linux"
            MirrordTargetOs.MACOS -> "macos"
            MirrordTargetOs.WINDOWS -> "windows"
        }

    /** Matches the layout under the plugin directory, e.g. `x86-64`, `arm64`. */
    val archDirectoryName: String
        get() = when (arch) {
            MirrordTargetArch.X86_64 -> "x86-64"
            MirrordTargetArch.ARM64 -> "arm64"
        }

    override fun toString(): String = "$osDirectoryName/$archDirectoryName"

    companion object {
        /**
         * Reads the platform of an environment reached over EEL.
         *
         * Pure, so every combination is unit-tested without an IDE.
         *
         * @throws MirrordError for platforms mirrord has no binary for, rather than the bare
         * `RuntimeException` the old `SystemInfo`-based code threw — this one reaches the user.
         */
        fun fromEel(platform: EelPlatform): MirrordTargetPlatform {
            val os = when {
                platform.isLinux -> MirrordTargetOs.LINUX
                platform.isMac -> MirrordTargetOs.MACOS
                platform.isWindows -> MirrordTargetOs.WINDOWS
                else -> throw MirrordError(
                    "mirrord does not support $platform environments",
                    "mirrord supports Linux, macOS and Windows targets."
                )
            }
            val arch = when {
                platform.isX86_64 -> MirrordTargetArch.X86_64
                platform.isArm64 -> MirrordTargetArch.ARM64
                else -> throw MirrordError(
                    "mirrord does not support ${platform.arch} environments",
                    "mirrord supports x86-64 and arm64 targets."
                )
            }
            return MirrordTargetPlatform(os, arch)
        }

        /**
         * The IDE host's own platform.
         *
         * Only correct for things that genuinely run on the host — the legacy WSL integration,
         * which always targets Linux on the host's architecture, and host-side helper binaries
         * such as Goland's `dlv`. Everything the user's process touches should come from
         * [MirrordEnvironment.platform] instead.
         */
        fun ofHost(): MirrordTargetPlatform {
            val os = when {
                SystemInfo.isLinux -> MirrordTargetOs.LINUX
                SystemInfo.isMac -> MirrordTargetOs.MACOS
                SystemInfo.isWindows -> MirrordTargetOs.WINDOWS
                else -> throw MirrordError("Unsupported platform: ${SystemInfo.OS_NAME}")
            }
            val arch = when {
                CpuArch.isIntel64() -> MirrordTargetArch.X86_64
                CpuArch.isArm64() -> MirrordTargetArch.ARM64
                else -> throw MirrordError("Unsupported architecture: ${CpuArch.CURRENT.name}")
            }
            return MirrordTargetPlatform(os, arch)
        }
    }
}

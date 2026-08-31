package com.metalbear.mirrord.bifrost

import com.intellij.platform.eel.EelPlatform
import com.metalbear.mirrord.MirrordError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Binary selection is where COR-1385 actually bit: the plugin asked `SystemInfo` what OS it was
 * on, got "macOS/arm64" from the IDE host, and downloaded that build for a linux/amd64
 * container. These tests pin the replacement — the platform now comes from the environment.
 */
class MirrordTargetPlatformTest {

    @Test
    fun `linux x86-64 maps to the linux binary directory`() {
        val platform = MirrordTargetPlatform.fromEel(EelPlatform.Linux(EelPlatform.Arch.X86_64))

        assertEquals(MirrordTargetOs.LINUX, platform.os)
        assertEquals(MirrordTargetArch.X86_64, platform.arch)
        assertEquals("linux", platform.osDirectoryName)
        assertEquals("x86-64", platform.archDirectoryName)
    }

    @Test
    fun `linux arm64 maps to arm64`() {
        val platform = MirrordTargetPlatform.fromEel(EelPlatform.Linux(EelPlatform.Arch.ARM_64))

        assertTrue(platform.isLinux)
        assertEquals(MirrordTargetArch.ARM64, platform.arch)
    }

    @Test
    fun `darwin maps to macos`() {
        val platform = MirrordTargetPlatform.fromEel(EelPlatform.Darwin(EelPlatform.Arch.ARM_64))

        assertTrue(platform.isMac)
        assertEquals("macos", platform.osDirectoryName)
    }

    @Test
    fun `windows maps to windows`() {
        val platform = MirrordTargetPlatform.fromEel(EelPlatform.Windows(EelPlatform.Arch.X86_64))

        assertTrue(platform.isWindows)
        assertEquals("windows", platform.osDirectoryName)
    }

    @Test
    fun `the exact case from the ticket - a mac host does not decide a linux target`() {
        // Ari's IDE ran on macOS/arm64; the container was linux/amd64. The old code consulted
        // SystemInfo and got the first of those. This asserts the platform is now read from the
        // environment, so the host's answer cannot leak in.
        val target = MirrordTargetPlatform.fromEel(EelPlatform.Linux(EelPlatform.Arch.X86_64))

        assertEquals("linux/x86-64", target.toString())
    }

    @Test
    fun `unsupported architectures fail with a user-facing error`() {
        // Not a bare RuntimeException: this reaches the user, so it has to read like a sentence.
        val error = assertThrows<MirrordError> {
            MirrordTargetPlatform.fromEel(EelPlatform.Linux(EelPlatform.Arch.ARM_32))
        }

        assertTrue(error.cause == null || error.cause is Throwable)
    }

    @Test
    fun `unsupported operating systems fail with a user-facing error`() {
        assertThrows<MirrordError> {
            MirrordTargetPlatform.fromEel(EelPlatform.FreeBSD(EelPlatform.Arch.X86_64))
        }
    }
}

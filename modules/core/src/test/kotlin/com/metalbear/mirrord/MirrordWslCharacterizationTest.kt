package com.metalbear.mirrord

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.wsl.WSLCommandLineOptions
import com.intellij.execution.wsl.WSLDistribution
import com.intellij.execution.wsl.WslDistributionManager
import com.intellij.openapi.util.SystemInfo
import com.metalbear.mirrord.bifrost.LegacyWslEnvironment
import com.metalbear.mirrord.bifrost.MirrordTargetArch
import com.metalbear.mirrord.bifrost.MirrordTargetOs
import com.metalbear.mirrord.bifrost.MirrordTargetPlatform
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * Characterization tests for the **current** WSL integration.
 *
 * They record how WSL works today, not how it should work, so that the move onto EEL fails
 * loudly if it changes observable behaviour.
 *
 * Run the suite twice after the migration, with "Use legacy WSL integration" on and off.
 * Identical results mean the swap preserved behaviour.
 *
 * **Requires a Windows host with at least one installed WSL distribution.** Elsewhere they report
 * as *skipped*, never as passed.
 */
class MirrordWslCharacterizationTest {

    /** Skips the calling test unless a real WSL distribution is available. */
    private fun requireWsl(): WSLDistribution {
        assumeTrue(SystemInfo.isWindows, "WSL tests require a Windows host")
        val distributions = WslDistributionManager.getInstance().installedDistributions
        assumeTrue(distributions.isNotEmpty(), "WSL tests require an installed WSL distribution")
        return distributions.first()
    }

    private fun wslOptionsAsUsedByPlugin() = WSLCommandLineOptions().apply {
        // Mirrors MirrordApi.prepareCommandLine. `isLaunchWithWslExe = true` steers
        // patchCommandLine away from IJent and down the legacy wsl.exe path.
        isLaunchWithWslExe = true
        isExecuteCommandInShell = false
    }

    // ---------------------------------------------------------------- path translation

    @Suppress("DEPRECATION")
    @Test
    fun `windows drive paths translate to mnt paths`() {
        val wsl = requireWsl()

        val translated = wsl.getWslPath("C:\\Users\\dev\\project\\.mirrord\\mirrord.json")

        assertEquals("/mnt/c/Users/dev/project/.mirrord/mirrord.json", translated)
    }

    @Suppress("DEPRECATION")
    @Test
    fun `already-linux paths are left alone by the plugin's fallback`() {
        val wsl = requireWsl()

        // MirrordExecManager.wslPath is `getWslPath(path) ?: path`. A path that already looks
        // like a distro-side path has no Windows equivalent, so getWslPath returns null and the
        // original survives. The custom-binary-path feature depends on this: a user pointing at
        // /home/me/mirrord/target/debug/mirrord must not have it mangled.
        val linuxPath = "/home/dev/mirrord/target/debug/mirrord"

        assertEquals(linuxPath, wsl.getWslPath(linuxPath) ?: linuxPath)
    }

    // ---------------------------------------------------------------- binary selection

    @Test
    fun `a wsl target selects the linux binary even on a windows host`() {
        val wsl = requireWsl()

        // The platform now comes from the environment rather than from SystemInfo, so this
        // asserts the same outcome through the new route.
        val platform = LegacyWslEnvironment(wsl, null).platform()
        val path = MirrordPathManager.getPath("mirrord", universalOnMac = true, platform = platform).path

        // WSL runs Linux binaries regardless of the host OS, and inherits the host architecture.
        assertTrue(
            path.endsWith(Paths.get("bin", "linux", "x86-64", "mirrord")),
            "expected a linux/x86-64 binary for a WSL target, got $path"
        )
        assertFalse(path.toString().endsWith(".exe"), "a WSL target must not get the .exe binary")
    }

    @Test
    fun `no wsl distribution on windows selects the native exe`() {
        assumeTrue(SystemInfo.isWindows, "requires a Windows host")

        val path = MirrordPathManager.getPath(
            "mirrord",
            universalOnMac = true,
            platform = MirrordTargetPlatform(MirrordTargetOs.WINDOWS, MirrordTargetArch.X86_64)
        ).path

        assertTrue(path.toString().endsWith("mirrord.exe"), "expected the native Windows binary, got $path")
    }

    // ---------------------------------------------------------------- classification

    @Test
    fun `a wsl target is not classified as windows-native`() {
        val wsl = requireWsl()

        // WSL takes the Linux LD_PRELOAD path, not pitm — even though the host is Windows.
        // isWinNative asks about the target, so this reads it directly rather than by proxy.
        assertFalse(LegacyWslEnvironment(wsl, null).platform().isWinNative)
        assertTrue(MirrordTargetPlatform(MirrordTargetOs.WINDOWS, MirrordTargetArch.X86_64).isWinNative)
    }

    // ---------------------------------------------------------------- command line patching

    @Test
    fun `patching rewrites the command to launch through wsl exe`() {
        val wsl = requireWsl()

        val commandLine = GeneralCommandLine("/home/dev/.local/bin/mirrord", "ext")
            .withParameters("-t", "pod/api")

        wsl.patchCommandLine(commandLine, null, wslOptionsAsUsedByPlugin())

        assertTrue(
            commandLine.exePath.endsWith("wsl.exe", ignoreCase = true),
            "expected the command to be launched via wsl.exe, got ${commandLine.exePath}"
        )

        val params = commandLine.parametersList.list
        assertEquals("--distribution", params[0])
        assertEquals(wsl.msId, params[1])
        assertEquals("--exec", params[2])
        assertEquals("/home/dev/.local/bin/mirrord", params[3])
        assertEquals(listOf("ext", "-t", "pod/api"), params.drop(4))
    }

    // ---------------------------------------------------------------- the WSLENV ordering question

    /**
     * Environment variables set *before* patching reach the distro.
     *
     * `patchCommandLine` builds `WSLENV` from the keys on the command line when it runs, so
     * interop forwards them. The plugin relies on this for `MIRRORD_EXT_PRINT_CONFIG`.
     */
    @Test
    fun `wslenv carries variables added before patching`() {
        val wsl = requireWsl()

        val commandLine = GeneralCommandLine("/usr/bin/mirrord", "ext")
        commandLine.withEnvironment("MIRRORD_EXT_PRINT_CONFIG", "TRUE")

        wsl.patchCommandLine(commandLine, null, wslOptionsAsUsedByPlugin())

        val wslenv = commandLine.environment["WSLENV"].orEmpty()
        assertTrue(
            wslenv.contains("MIRRORD_EXT_PRINT_CONFIG"),
            "expected MIRRORD_EXT_PRINT_CONFIG to be registered for interop, WSLENV was: $wslenv"
        )
    }

    /**
     * **This test encodes a hypothesis, not a known fact — read the failure message before "fixing" it.**
     *
     * `MirrordCliTask.prepareCommandLine` patches the command line, then adds four more
     * variables after it. If `WSLENV` is a snapshot taken at patch time, those four never reach
     * the Linux `mirrord` process.
     *
     * - **Passes** → the ordering bug is real, and `MIRRORD_PROGRESS_MODE=json` has not reached
     *   the CLI under WSL. File it, and make env-vs-patch ordering explicit.
     * - **Fails** → `WSLENV` is not a snapshot. Delete this test.
     */
    @Test
    fun `wslenv omits variables added after patching`() {
        val wsl = requireWsl()

        val commandLine = GeneralCommandLine("/usr/bin/mirrord", "ext")
        commandLine.withEnvironment("SET_BEFORE_PATCH", "1")

        wsl.patchCommandLine(commandLine, null, wslOptionsAsUsedByPlugin())

        // Exactly what prepareCommandLine does after patching.
        commandLine.withEnvironment("MIRRORD_PROGRESS_MODE", "json")

        val wslenv = commandLine.environment["WSLENV"].orEmpty()
        assertTrue(wslenv.contains("SET_BEFORE_PATCH"), "sanity check: WSLENV was $wslenv")
        assertFalse(
            wslenv.contains("MIRRORD_PROGRESS_MODE"),
            "WSLENV picked up a variable added after patching, so the suspected ordering bug " +
                "does not exist. See this test's KDoc — update the architecture notes rather " +
                "than changing this assertion. WSLENV was: $wslenv"
        )
    }

    // ---------------------------------------------------------------- the second execution path

    /**
     * `MirrordBinaryManager` probes for a binary with `executeOnWsl`, which bypasses
     * `GeneralCommandLine` and `ProcessBuilder` entirely. Any replacement for the WSL path has to
     * cover this too, so it is worth pinning that it works at all.
     */
    @Test
    fun `short probes run inside the distribution`() {
        val wsl = requireWsl()

        val output = wsl.executeOnWsl(5_000, "uname", "-s")

        assertEquals(0, output.exitCode, "uname failed in WSL: ${output.stderr}")
        assertEquals("Linux", output.stdout.trim())
    }
}

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
 * These do not describe how WSL *should* work — they record how it *does* work today, before
 * WSL is moved onto the IntelliJ Platform's execution environment layer (EEL). The point is to
 * have something that fails loudly if the migration changes observable behaviour, because until
 * now the WSL path has had no test coverage at all.
 *
 * After the migration, run this suite twice — once with "Use legacy WSL integration" on and once
 * off. Identical results mean the swap was behaviour-preserving. Any difference is either a bug
 * or a deliberate change that belongs in the changelog.
 *
 * **These tests require a Windows host with at least one installed WSL distribution.** On any
 * other machine they report as *skipped*, never as passed — a vacuous pass would be worse than
 * no test, since it would imply coverage that does not exist. (Note that the existing
 * `MirrordPitmJdkTest` uses an early `return` instead, so it reports green on Linux while
 * asserting nothing.)
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
        // Mirrors MirrordApi.prepareCommandLine. `isLaunchWithWslExe = true` deliberately steers
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
        // isWinNative now asks about the target, so this reads directly rather than by proxy.
        assertFalse(isWinNative(LegacyWslEnvironment(wsl, null).platform()))
        assertTrue(isWinNative(MirrordTargetPlatform(MirrordTargetOs.WINDOWS, MirrordTargetArch.X86_64)))
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
     * `patchCommandLine` builds `WSLENV` from the keys present on the command line at the moment
     * it runs, so Win32/WSL interop forwards them. This is the case the plugin relies on for
     * `MIRRORD_EXT_PRINT_CONFIG` and the run configuration's own variables.
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
     * `MirrordCliTask.prepareCommandLine` patches the command line at MirrordApi.kt:766-772, but
     * then adds `MIRRORD_PROGRESS_MODE`, `MIRRORD_PROGRESS_SUPPORT_IDE`, `MIRRORD_IDE_NAME`
     * (:800-802) and `MIRRORD_BRANCH_NAME` (:789) *after* it. If `WSLENV` is a snapshot taken at
     * patch time, those four never reach the Linux `mirrord` process under WSL.
     *
     * - **Test passes** → the ordering bug is real, and `MIRRORD_PROGRESS_MODE=json` has not been
     *   reaching the CLI under WSL. That is a shipped bug worth its own issue, and it means the
     *   new abstraction must make env-vs-patch ordering explicit rather than positional.
     * - **Test fails** → `WSLENV` is not a snapshot and the hypothesis is wrong. Good news. Delete
     *   this test and correct `documents/COR-1385/architecture.md`, which currently records it as
     *   a suspected live bug.
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

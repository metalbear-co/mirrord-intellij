package com.metalbear.mirrord.bifrost

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessOutput
import com.intellij.execution.wsl.WSLCommandLineOptions
import com.intellij.execution.wsl.WSLDistribution
import com.intellij.openapi.project.Project
import com.metalbear.mirrord.MirrordLogger

/**
 * The old road, kept open beside the bridge.
 *
 * WSL runs through [EelEnvironment] by default now. This exists purely as an escape hatch: if a
 * WSL setup regresses under EEL, the user flips "Use legacy WSL integration" in settings and
 * gets exactly the behaviour they had before, without downgrading the plugin.
 *
 * Every line here is lifted from the code it replaces, deliberately unimproved. `getWslPath` is
 * still the deprecated `String` overload, the command-line options are still the same two flags,
 * and probes still go through `executeOnWsl`. Tidying any of it would make this a *different*
 * implementation, which is the one thing it must not be — WSL has no test coverage on Linux, so
 * "identical to what shipped" is the only guarantee available. Delete the whole file once
 * EEL-backed WSL has been validated on a Windows machine.
 */
class LegacyWslEnvironment(
    private val distribution: WSLDistribution,
    private val project: Project?
) : MirrordEnvironment {

    override val name: String = "WSL/legacy:${distribution.msId}"

    override val isLocal: Boolean = false

    /**
     * WSL always runs Linux, on the host's architecture — which is what the old
     * `treatAsLinux = SystemInfo.isLinux || wsl != null` plus `CpuArch.CURRENT` amounted to.
     */
    override fun platform(): MirrordTargetPlatform =
        MirrordTargetPlatform(MirrordTargetOs.LINUX, MirrordTargetPlatform.ofHost().arch)

    /** Was `MirrordExecManager.wslPath`: `wslDistribution?.getWslPath(path) ?: path`. */
    @Suppress("DEPRECATION")
    override fun resolve(path: HostPath): TargetPath {
        val raw = path.path.toString()
        // The String overload, not the Path one. They are equivalent today, but the deprecated
        // one is what shipped and what MirrordWslCharacterizationTest pins.
        return TargetPath(distribution.getWslPath(raw) ?: raw)
    }

    /** WSL sees the host filesystem under /mnt, so nothing is ever copied. */
    // No `onCopy`: WSL reaches the host filesystem through /mnt, so this is a path
    // translation and no bytes move.
    override fun provide(path: HostPath, name: String, onCopy: (Long) -> Unit): TargetPath = resolve(path)

    override fun spawn(spec: MirrordProcessSpec): Process {
        val commandLine = GeneralCommandLine(spec.executable.value)
            .withParameters(spec.args)
            .withEnvironment(spec.env)
        spec.workingDirectory?.let { commandLine.withWorkDirectory(it.value) }

        val wslOptions = WSLCommandLineOptions().apply {
            isLaunchWithWslExe = true
            isExecuteCommandInShell = false
        }
        distribution.patchCommandLine(commandLine, project, wslOptions)

        MirrordLogger.logger.info("mirrord.bifrost: spawned ${spec.describe()} env=$name side=target (wsl.exe)")

        return commandLine.toProcessBuilder()
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()
    }

    /**
     * Was `wslDistribution.executeOnWsl(5000, command, "--version")`.
     *
     * Kept off [spawn] on purpose: `executeOnWsl` defaults to `isExecuteCommandInShell = true`
     * and `isLaunchWithWslExe = false` — the exact opposite of the options above — so routing
     * probes through [spawn] would quietly change how they run.
     */
    override fun probe(executable: TargetPath, args: List<String>, timeoutMillis: Long): ProcessOutput =
        distribution.executeOnWsl(timeoutMillis.toInt(), executable.value, *args.toTypedArray())
}

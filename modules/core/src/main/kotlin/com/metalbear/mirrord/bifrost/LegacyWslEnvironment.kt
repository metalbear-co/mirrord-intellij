package com.metalbear.mirrord.bifrost

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessOutput
import com.intellij.execution.wsl.WSLCommandLineOptions
import com.intellij.execution.wsl.WSLDistribution
import com.intellij.openapi.project.Project
import com.metalbear.mirrord.MirrordLogger

/**
 * The `wsl.exe` integration that shipped before EEL, behind the "Use legacy WSL integration"
 * setting.
 *
 * WSL runs through [EelEnvironment] by default now. This is the escape hatch: if a WSL setup
 * regresses under EEL, the user turns the setting on and gets the previous behaviour back
 * without downgrading the plugin.
 *
 * Every line here is lifted from the code it replaces and left unimproved, because "identical to
 * what shipped" is the only guarantee available — WSL has no test coverage on Linux. Delete the
 * file once EEL-backed WSL is validated on a Windows machine.
 */
class LegacyWslEnvironment(
    private val distribution: WSLDistribution,
    private val project: Project?
) : MirrordEnvironment {

    override val name: String = "WSL/legacy:${distribution.msId}"

    override val isLocal: Boolean = false

    /** WSL always runs Linux, on the host's architecture. */
    override fun platform(): MirrordTargetPlatform =
        MirrordTargetPlatform(MirrordTargetOs.LINUX, MirrordTargetPlatform.ofHost().arch)

    /** Was `MirrordExecManager.wslPath`: `wslDistribution?.getWslPath(path) ?: path`. */
    @Suppress("DEPRECATION")
    override fun resolve(path: HostPath): TargetPath {
        val raw = path.path.toString()
        // The String overload, not the Path one. Equivalent today, but the deprecated one is
        // what shipped and what MirrordWslCharacterizationTest pins.
        return TargetPath(distribution.getWslPath(raw) ?: raw)
    }

    /** WSL reaches the host filesystem under /mnt, so this translates a path and copies nothing. */
    override fun provide(path: HostPath, name: String, onCopy: (Long) -> Unit): TargetPath = resolve(path)

    /** Was `which <executable>` through `executeOnWsl`. Kept, so WSL behaviour does not move. */
    override fun locate(executable: String): TargetPath? =
        probe(TargetPath("which"), listOf(executable), LOCATE_TIMEOUT_MILLIS)
            .stdout
            .lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.let { TargetPath(it) }

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
     * Kept off [spawn]: `executeOnWsl` defaults to `isExecuteCommandInShell = true` and
     * `isLaunchWithWslExe = false`, the opposite of the options above.
     */
    override fun probe(executable: TargetPath, args: List<String>, timeoutMillis: Long): ProcessOutput =
        distribution.executeOnWsl(timeoutMillis.toInt(), executable.value, *args.toTypedArray())

    private companion object {
        const val LOCATE_TIMEOUT_MILLIS = 5_000L
    }
}

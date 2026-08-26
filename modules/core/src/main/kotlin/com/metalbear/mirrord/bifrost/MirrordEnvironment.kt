package com.metalbear.mirrord.bifrost

import com.intellij.execution.process.ProcessOutput

/**
 * A complete description of a process to run in a [MirrordEnvironment].
 *
 * [env] is assembled in full before [MirrordEnvironment.spawn] is called, which is why this is
 * a spec rather than a command line. The code it replaces built a `GeneralCommandLine`, patched
 * it for WSL partway through, then kept adding variables, so whether a variable reached the
 * process depended on statement order.
 *
 * [env] is overlaid on the target's own environment, matching the `ParentEnvironmentType.CONSOLE`
 * behaviour of the `GeneralCommandLine` this replaces.
 */
data class MirrordProcessSpec(
    val executable: TargetPath,
    val args: List<String>,
    val env: Map<String, String>,
    val workingDirectory: TargetPath?
) {
    /** For logs. Omits environment *values* — they routinely carry credentials. */
    fun describe(): String = "$executable ${args.joinToString(" ")} [${env.size} env vars]"
}

/**
 * Where mirrord runs: this machine, a WSL distribution, a dev container, or an SSH host.
 *
 * Callers do not ask which. Code that stops to ask "am I local?" is code that answers wrong the
 * first time a new environment kind appears — which is COR-1385, where the plugin asked the IDE's
 * own machine what OS it was on and downloaded a macOS binary for a Linux container.
 *
 * Every method performs I/O, because reaching a dev container can mean starting and deploying an
 * agent. **None of them may be called on the EDT.** [MirrordBifrostTracer] enforces that and
 * bounds each call with a timeout.
 */
interface MirrordEnvironment {
    /** Human-readable; appears in every log line and in user-facing errors. */
    val name: String

    /**
     * True when the far end is the IDE's own machine.
     *
     * Not for branching the main path — JetBrains document that as an anti-pattern. It exists
     * for the two cases they do sanction: work that belongs on the IDE host, and port forwarding.
     */
    val isLocal: Boolean

    /** OS and architecture at the far end. Cached after the first crossing. */
    fun platform(): MirrordTargetPlatform

    /**
     * Translates a host path into the path the target uses for the same file.
     *
     * @throws com.metalbear.mirrord.MirrordError if the file is not visible from the target.
     */
    fun resolve(path: HostPath): TargetPath

    /**
     * Makes a host file available at the far end, copying it across only if the target cannot
     * already see it, and returns its target path.
     *
     * This is what makes dev containers work: the plugin-managed mirrord binary lives in the
     * IDE's plugin directory, which a container cannot see at any path.
     *
     * @param name basename to use if a copy is made.
     * @param onCopy invoked with the file size, and only when bytes actually move. A caller that
     *   warns about a slow transfer must use this rather than guessing beforehand: for a local
     *   target, and for legacy WSL, nothing is copied.
     */
    fun provide(path: HostPath, name: String, onCopy: (Long) -> Unit = {}): TargetPath

    /** Finds [executable] on the target's `PATH`, or null when it is not there. */
    fun locate(executable: String): TargetPath?

    /** Spawns [spec] at the far end. The returned process is an ordinary [Process]. */
    fun spawn(spec: MirrordProcessSpec): Process

    /**
     * Runs a short command and waits for it, such as `mirrord --version`.
     *
     * Separate from [spawn] because the legacy WSL path reaches these through `executeOnWsl`,
     * whose defaults are the opposite of the ones it uses for long-running commands
     * (`isExecuteCommandInShell = true`, `isLaunchWithWslExe = false`). Routing probes through
     * [spawn] would change WSL behaviour, and this refactor leaves WSL bit-for-bit identical.
     */
    fun probe(executable: TargetPath, args: List<String>, timeoutMillis: Long): ProcessOutput
}

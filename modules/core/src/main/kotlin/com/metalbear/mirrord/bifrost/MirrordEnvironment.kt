package com.metalbear.mirrord.bifrost

import com.intellij.execution.process.ProcessOutput

/**
 * The rainbow bridge: one span from the IDE to wherever the user's code actually runs.
 *
 * The far end might be this very machine, a WSL distribution, or a dev container. Callers are
 * not supposed to know which, and that is the entire design. Code that stops halfway across to
 * ask "am I local?" is code that will eventually answer wrong — which is precisely how COR-1385
 * happened, where the plugin asked the IDE's own machine what OS it was on and confidently
 * downloaded a macOS binary for a Linux container.
 *
 * The bridge is transparent in both senses. You cannot see it from the call sites, which is the
 * point. And nobody has to give anything up to cross it: local runs behave exactly as they
 * always have, WSL keeps its old road open right beside it, and dev containers finally get
 * across at all. Everyone reaches the other side, everyone stays happy, and nobody looks down.
 */

/**
 * A complete description of a process to run in a [MirrordEnvironment].
 *
 * [env] is assembled in full *before* [MirrordEnvironment.spawn] is called, and that is the
 * whole reason this type exists rather than passing a command line around. The old code built a
 * `GeneralCommandLine`, patched it for WSL halfway through, and then kept adding environment
 * variables afterwards — so `MIRRORD_PROGRESS_MODE`, `MIRRORD_PROGRESS_SUPPORT_IDE`,
 * `MIRRORD_IDE_NAME` and `MIRRORD_BRANCH_NAME` were registered for WSL interop only if you were
 * lucky with statement order. With a spec there is no "halfway through" left to get wrong.
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
    /** For logs. Deliberately omits environment *values* — they routinely carry credentials. */
    fun describe(): String = "$executable ${args.joinToString(" ")} [${env.size} env vars]"
}

/**
 * Where mirrord runs.
 *
 * Every method may perform I/O — reaching a dev container can mean starting and deploying an
 * agent — so **none of them may be called on the EDT**. [MirrordBifrostTracer] enforces that
 * with a tripwire and bounds each call with a timeout.
 */
interface MirrordEnvironment {
    /** Human-readable; appears in every log line and in user-facing errors. */
    val name: String

    /**
     * True when the far end of the bridge is the IDE's own machine.
     *
     * Deliberately *not* used to branch the main path — JetBrains document that as an
     * anti-pattern, and converting a local descriptor is instant anyway. It exists for the two
     * cases they do sanction: work that genuinely belongs on the IDE host, and port forwarding.
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
     * Makes a host file available at the far end, copying it across only if it is not already
     * visible there, and returns its target path.
     *
     * This is the step that makes dev containers work at all: the plugin-managed mirrord binary
     * lives in the IDE's plugin directory, which a container cannot see at any path.
     *
     * @param name basename to use if a copy is made.
     * @param onCopy invoked with the file size, and only when bytes actually move. Callers that
     *   warn the user about a slow transfer must use this rather than guessing beforehand: for a
     *   local target, and for legacy WSL, this method copies nothing at all.
     */
    fun provide(path: HostPath, name: String, onCopy: (Long) -> Unit = {}): TargetPath

    /** Spawns [spec] at the far end. The returned process is an ordinary [Process]. */
    fun spawn(spec: MirrordProcessSpec): Process

    /**
     * Runs a short command and waits for it — `mirrord --version`, `which mirrord`.
     *
     * Separate from [spawn] because the legacy WSL path reaches these through `executeOnWsl`,
     * whose defaults are the exact opposite of the ones it uses for long-running commands
     * (`isExecuteCommandInShell = true`, `isLaunchWithWslExe = false`). Routing probes through
     * [spawn] would quietly change WSL behaviour, and this refactor is meant to leave WSL
     * bit-for-bit identical.
     */
    fun probe(executable: TargetPath, args: List<String>, timeoutMillis: Long): ProcessOutput
}

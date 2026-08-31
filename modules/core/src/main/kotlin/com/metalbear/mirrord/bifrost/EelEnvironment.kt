package com.metalbear.mirrord.bifrost

import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessOutput
import com.intellij.platform.eel.EelApi
import com.intellij.platform.eel.EelDescriptor
import com.intellij.platform.eel.path.EelPath
import com.intellij.platform.eel.provider.LocalEelDescriptor
import com.intellij.platform.eel.provider.asEelPath
import com.intellij.platform.eel.provider.asNioPath
import com.intellij.platform.eel.provider.toEelApi
import com.intellij.platform.eel.provider.utils.EelPathUtils
import com.intellij.platform.eel.spawnProcess
import com.intellij.util.io.sha256Hex
import com.metalbear.mirrord.MirrordError
import com.metalbear.mirrord.MirrordLogger
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.util.UUID

/**
 * Runs a fixed set of operations against whichever environment a project lives in — local, WSL,
 * Docker, a dev container, or SSH — so that callers never branch on which one it is.
 *
 * | Member | What it does |
 * |---|---|
 * | [platform] | OS and architecture of the target |
 * | [resolve] | host path to target path |
 * | [provide] | copy a host file across, if the target cannot already see it |
 * | [locate] | find an executable on the target's `PATH` |
 * | [spawn] | start a process at the target |
 * | [probe] | run a short command and wait for it |
 *
 * All of it goes through [EelApi], the platform's own abstraction over these environments.
 *
 * There is no `if (descriptor is LocalEelDescriptor)` branch on the main path. JetBrains
 * document that check as an anti-pattern, and converting a local descriptor does no I/O, so the
 * special case would add a second code path and nothing else. [isLocal] exists for the two
 * things they do sanction: work that belongs on the IDE host, and port forwarding.
 */
class EelEnvironment(
    private val descriptor: EelDescriptor,
    private val tracer: MirrordBifrostTracer = MirrordBifrostTracer.shared
) : MirrordEnvironment {

    override val name: String = descriptor.name

    override val isLocal: Boolean = descriptor === LocalEelDescriptor

    /** The first crossing can start and deploy an agent, so it is done once and reused. */
    private val api: EelApi by lazy {
        tracer.crossing("connect", name) { descriptor.toEelApi() }
    }

    private val cachedPlatform: MirrordTargetPlatform by lazy {
        MirrordTargetPlatform.fromEel(api.platform).also {
            MirrordLogger.logger.info(
                "mirrord.bifrost: platform target=$it host=${MirrordTargetPlatform.ofHost()} " +
                    "mismatch=${it != MirrordTargetPlatform.ofHost()} env=$name"
            )
        }
    }

    /**
     * The environment variables a login shell would see at the target.
     *
     * Read with `EelExecApi.fetchLoginShellEnvVariables`, which starts a login shell there and
     * returns its full variable set. See `EelExecApi` in `com.intellij.platform.eel`.
     *
     * Every spawn needs this. `EelExecApi` takes the *complete* environment for a process, where
     * `GeneralCommandLine` inherits the parent's by default. Passing only mirrord's own variables
     * compiles, then strips `PATH`, `HOME` and `KUBECONFIG` from the CLI, and every Kubernetes
     * call fails with an authentication error that looks nothing like its cause.
     */
    private val targetEnvVariables: Map<String, String> by lazy {
        tracer.crossing("fetch-env", name) { api.exec.fetchLoginShellEnvVariables() }.also {
            MirrordLogger.logger.info("mirrord.bifrost: target env variables resolved vars=${it.size} env=$name")
        }
    }

    override fun platform(): MirrordTargetPlatform = cachedPlatform

    override fun resolve(path: HostPath): TargetPath =
        try {
            TargetPath(path.path.asEelPath().toString())
        } catch (e: Throwable) {
            throw MirrordError(
                "mirrord cannot see `$path` from $name.",
                "The file has to be reachable from the environment mirrord runs in. If it lives " +
                    "outside the project, move it inside or point mirrord at a copy that does.",
                e
            )
        }

    /**
     * Provides a file to the target environment, hashing its contents with SHA-256 so that
     * needless copies do not happen.
     *
     * The hash is part of the destination path. That way one hash answers the question, instead
     * of hashing both the file to stage and whatever is already staged there.
     */
    override fun provide(path: HostPath, name: String, onCopy: (Long) -> Unit): TargetPath {
        // Nothing moves if the target can already see the file. That covers local, and WSL
        // through the UNC root, so only containers pay for a transfer.
        //
        // The existence check is what makes this correct. `resolve` says only whether a path can
        // be *expressed* in the target's terms, not whether the file is there — for a dev
        // container it hands back a host path that exists nowhere inside the container.
        runCatching { resolve(path) }.getOrNull()?.let { candidate ->
            val reachable = runCatching {
                Files.exists(EelPath.parse(candidate.value, descriptor).asNioPath())
            }.getOrDefault(false)

            if (reachable) {
                MirrordLogger.logger.info("mirrord.bifrost: provide VISIBLE name=$name target=$candidate env=${this.name}")
                return candidate
            }
            MirrordLogger.logger.info(
                "mirrord.bifrost: provide NOT-VISIBLE name=$name candidate=$candidate env=${this.name} — staging a copy"
            )
        }

        if (platform().isWindows) {
            return tracer.crossing("provide", this.name) {
                // Uncached, and no permission step: a Windows target has no `/tmp` to anchor a
                // stable path to, and no POSIX mode bits to set.
                val copied = EelTransferCompat.transferLocalContentToRemote(
                    path.path,
                    EelPathUtils.TransferTarget.Temporary(descriptor)
                )
                TargetPath(copied.asEelPath().toString())
            }
        }

        val digest = sha256Prefix(path)
        val destination = EelPath.parse("/tmp/mirrord-ide/$digest/$name", descriptor)
        val destinationNio = destination.asNioPath()

        if (tracer.crossing("provide-check", this.name) { Files.exists(destinationNio) }) {
            MirrordLogger.logger.info(
                "mirrord.bifrost: provide CACHED name=$name sha=$digest target=$destination env=${this.name}"
            )
            return TargetPath(destination.toString())
        }

        return tracer.crossing("provide", this.name) {
            val bytes = runCatching { Files.size(path.path) }.getOrDefault(-1L)
            MirrordLogger.logger.info(
                "mirrord.bifrost: provide COPY name=$name sha=$digest host=$path target=$destination " +
                    "bytes=$bytes env=${this.name}"
            )
            onCopy(bytes)

            // Make sure the parent directories exist. An explicit transfer target writes the
            // file but does not create them, and the path is routed, so this runs in the target.
            Files.createDirectories(destinationNio.parent)

            // We first move to a temporary path, and after we are sure the move succeeded, move
            // to the true target path, to prevent incomplete host-to-remote transfers.
            //
            // The cache check above is only `exists`, so a truncated file left at the
            // content-addressed path would be served as a cache hit forever.
            val staging = destinationNio.resolveSibling("$name.${UUID.randomUUID()}.partial")
            try {
                EelTransferCompat.transferLocalContentToRemote(
                    path.path,
                    EelPathUtils.TransferTarget.Explicit(staging)
                )
                // Before the move, so the file is never visible at its final path without the bit.
                makeExecutable(staging)
                runCatching { Files.move(staging, destinationNio, StandardCopyOption.ATOMIC_MOVE) }
                    .recoverCatching {
                        Files.move(staging, destinationNio, StandardCopyOption.REPLACE_EXISTING)
                    }
                    .getOrThrow()
            } catch (e: Throwable) {
                // If any step failed, delete the temporary file too.
                runCatching { Files.deleteIfExists(staging) }
                throw e
            }
            TargetPath(destination.toString())
        }
    }

    /** `EelExecApi.findExeFilesInPath` is the platform's own `which`, run at the target. */
    override fun locate(executable: String): TargetPath? =
        tracer.crossing("locate", name) { api.exec.findExeFilesInPath(executable) }
            .firstOrNull()
            ?.let { TargetPath(it.toString()) }

    /** The EEL process is converted to a plain [Process], so nothing downstream knows the difference. */
    override fun spawn(spec: MirrordProcessSpec): Process =
        tracer.crossing("spawn", name) {
            api.exec.spawnProcess(spec.executable.value)
                .args(spec.args)
                .env(targetEnvVariables + spec.env)
                .apply { spec.workingDirectory?.let { workingDirectory(EelPath.parse(it.value, descriptor)) } }
                .eelIt()
        }.convertToJavaProcess().also {
            MirrordLogger.logger.info("mirrord.bifrost: spawned ${spec.describe()} env=$name side=${if (isLocal) "host" else "target"}")
        }

    /** Runs a short command at the target and waits for it. */
    override fun probe(executable: TargetPath, args: List<String>, timeoutMillis: Long): ProcessOutput {
        val spec = MirrordProcessSpec(executable, args, emptyMap(), null)

        // `CapturingProcessHandler` drains both pipes at once and enforces the timeout. By hand
        // this needs two reader threads: a child that fills the stderr pipe while this side reads
        // stdout deadlocks, and reading stdout to EOF first blocks until the child exits.
        val output = CapturingProcessHandler(spawn(spec), StandardCharsets.UTF_8, spec.describe())
            .runProcess(timeoutMillis.toInt())

        if (output.isTimeout) {
            throw MirrordError("`$executable ${args.joinToString(" ")}` did not finish within ${timeoutMillis}ms in $name")
        }
        return output
    }

    /**
     * Sets the mode bits on a freshly staged file.
     *
     * **EEL COMPAT 261/262** — the platform's transfer-attribute strategy type moved between
     * builds, so the transfer goes through `EelTransferCompat` and the permissions are set here
     * instead. See `EelTransferCompat` for the full account.
     */
    private fun makeExecutable(target: Path) {
        runCatching { Files.setPosixFilePermissions(target, EXECUTABLE_PERMISSIONS) }
            .onFailure {
                MirrordLogger.logger.warn(
                    "mirrord.bifrost: could not set the executable bit on $target in $name — " +
                        "mirrord will not be runnable there",
                    it
                )
            }
    }

    private fun sha256Prefix(path: HostPath): String = sha256Hex(path.path).take(16)

    private companion object {
        /** 0755 — the staged CLI must be runnable by whichever user the target runs as. */
        val EXECUTABLE_PERMISSIONS: Set<PosixFilePermission> = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_EXECUTE
        )
    }
}

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
 * The one implementation that carries everything: local, WSL, Docker, dev containers, SSH.
 *
 * There is deliberately no `if (descriptor is LocalEelDescriptor)` branch on the main path.
 * JetBrains document that check as an anti-pattern, and converting a local descriptor is
 * instant and does no I/O, so a special case would buy a second code path to maintain and
 * nothing else. [isLocal] exists only for the two things they do sanction — work that belongs
 * on the IDE host, and port forwarding.
 */
class EelEnvironment(
    private val descriptor: EelDescriptor,
    private val tracer: MirrordBifrostTracer = MirrordBifrostTracer.shared
) : MirrordEnvironment {

    override val name: String = descriptor.name

    override val isLocal: Boolean = descriptor === LocalEelDescriptor

    /**
     * The first crossing may start and deploy an agent, so it is done once and reused. Every
     * later call is cheap.
     */
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
     * The target's own environment.
     *
     * This is load-bearing and easy to miss. `EelExecApi` takes the *complete* environment for a
     * process — unlike `GeneralCommandLine`, which defaults to inheriting the parent's. Passing
     * only mirrord's own variables would compile perfectly and then strip `PATH`, `HOME` and
     * `KUBECONFIG` from the CLI, so every Kubernetes call would fail with an authentication
     * error that looks nothing like its cause.
     */
    private val baseEnvironment: Map<String, String> by lazy {
        tracer.crossing("fetch-env", name) { api.exec.fetchLoginShellEnvVariables() }.also {
            MirrordLogger.logger.info("mirrord.bifrost: base environment resolved vars=${it.size} env=$name")
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
     * Content-addressed staging: the SHA-256 of the file is part of its destination path.
     *
     * A version-keyed path would be wrong for the case that matters most day to day — rebuilding
     * mirrord locally produces different bytes under the same version string, and the stale copy
     * would win silently. Hashing the content makes that impossible, and makes the cache check a
     * single `exists` rather than a re-read of ~100 MB.
     *
     * Windows targets fall back to an uncached temporary copy: there is no `/tmp` to anchor a
     * stable path to, and the dev-container case this exists for is POSIX.
     */
    override fun provide(path: HostPath, name: String, onCopy: (Long) -> Unit): TargetPath {
        // Already visible from the target? Then nothing needs to move — true for local, and for
        // WSL via the UNC root, so only containers ever pay for a transfer.
        //
        // The existence check is the load-bearing part. `resolve` only says whether a path can
        // be *expressed* in the target's terms, not whether the file is actually there: for a
        // dev container it will cheerfully hand back a host path that exists nowhere inside the
        // container. Trusting it skipped the transfer entirely and left mirrord looking for a
        // 73 MB binary that had never been copied across.
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
                // No permission step: Windows targets have no POSIX mode bits to set.
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

            // An explicit transfer target writes the file but will not create the directories
            // leading to it, so a first copy into a fresh container fails with NoSuchFileException
            // on a path that reads as though the *source* were missing. These are routed paths, so
            // this creates the directory inside the target.
            Files.createDirectories(destinationNio.parent)

            // Transfer to a unique temporary name and move it into place. The cache check above is
            // only `exists`, so a transfer interrupted by a timeout, a cancel, or a container
            // restart would otherwise leave a truncated file at the content-addressed path — where
            // it is served as a valid cache hit forever, because the hash names the *source*
            // bytes and never gets re-verified. `updateBinary` already writes host-side files this
            // way; the target side needs the same guarantee.
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
                runCatching { Files.deleteIfExists(staging) }
                throw e
            }
            TargetPath(destination.toString())
        }
    }

    override fun spawn(spec: MirrordProcessSpec): Process =
        tracer.crossing("spawn", name) {
            api.exec.spawnProcess(spec.executable.value)
                .args(spec.args)
                .env(baseEnvironment + spec.env)
                .apply { spec.workingDirectory?.let { workingDirectory(EelPath.parse(it.value, descriptor)) } }
                .eelIt()
        }.convertToJavaProcess().also {
            MirrordLogger.logger.info("mirrord.bifrost: spawned ${spec.describe()} env=$name side=${if (isLocal) "host" else "target"}")
        }

    override fun probe(executable: TargetPath, args: List<String>, timeoutMillis: Long): ProcessOutput {
        val spec = MirrordProcessSpec(executable, args, emptyMap(), null)

        // `CapturingProcessHandler` drains both pipes concurrently and enforces the timeout. Doing
        // it by hand needs two reader threads to avoid the deadlock where a child fills the stderr
        // pipe while this side is still reading stdout — and reading stdout to EOF first makes the
        // timeout unreachable, because that read blocks until the child exits.
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
     * Deliberately not done with the platform's transfer-attribute strategy. That type is
     * `EelPathUtils.FileTransferAttributesStrategy` in 2026.1 but a top-level
     * `EelFileTransferAttributesStrategy` in 2026.2, which changes the *method descriptor* of
     * every `transferLocalContentToRemote` overload that accepts it. A plugin compiled against
     * one build then dies on the other: compiled against 2026.1 and run on 2026.2, it raises
     * `ClassNotFoundException` while staging the CLI into a dev container. This is the same
     * 261/262 churn already noted for `EelUnavailableException` in [MirrordBifrostTracer].
     *
     * The two-argument overload is byte-identical across both builds, so the transfer uses that
     * and the permissions are applied here. Doing it explicitly also removes the old ordering
     * trap where the executable bit was set on a file that was about to be replaced.
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

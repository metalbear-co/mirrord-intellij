package com.metalbear.mirrord.bifrost

import com.intellij.platform.eel.EelApi
import com.intellij.platform.eel.EelDescriptor
import com.intellij.platform.eel.path.EelPath
import com.intellij.platform.eel.provider.LocalEelDescriptor
import com.intellij.platform.eel.provider.asEelPath
import com.intellij.platform.eel.provider.asNioPath
import com.intellij.platform.eel.provider.toEelApi
import com.intellij.platform.eel.provider.utils.EelPathUtils
import com.intellij.platform.eel.spawnProcess
import com.metalbear.mirrord.MirrordError
import com.metalbear.mirrord.MirrordLogger
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

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
    override fun provide(path: HostPath, name: String): TargetPath {
        // Already visible from the target? Then nothing needs to move. True for local, and for
        // WSL via the UNC root, so only containers ever pay for a transfer.
        runCatching { resolve(path) }.getOrNull()?.let {
            MirrordLogger.logger.info("mirrord.bifrost: provide VISIBLE name=$name target=$it env=${this.name}")
            return it
        }

        val strategy = EelPathUtils.FileTransferAttributesStrategy.copyWithRequiredPosixPermissions(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_EXECUTE
        )

        if (platform().isWindows) {
            return tracer.crossing("provide", this.name) {
                val copied = EelPathUtils.transferLocalContentToRemote(
                    path.path,
                    EelPathUtils.TransferTarget.Temporary(descriptor),
                    strategy
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
            MirrordLogger.logger.info(
                "mirrord.bifrost: provide COPY name=$name sha=$digest host=$path target=$destination " +
                    "bytes=${runCatching { Files.size(path.path) }.getOrDefault(-1L)} env=${this.name}"
            )
            EelPathUtils.transferLocalContentToRemote(
                path.path,
                EelPathUtils.TransferTarget.Explicit(destinationNio),
                strategy
            )
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

    override fun probe(executable: TargetPath, args: List<String>, timeoutMillis: Long): MirrordProbeOutput {
        val process = spawn(MirrordProcessSpec(executable, args, emptyMap(), null))
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        val finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            throw MirrordError("`$executable ${args.joinToString(" ")}` did not finish within ${timeoutMillis}ms in $name")
        }
        return MirrordProbeOutput(process.exitValue(), stdout, stderr)
    }

    private fun sha256Prefix(path: HostPath): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path.path).use { stream ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.take(16)
    }
}

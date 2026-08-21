package com.metalbear.mirrord.bifrost

import com.intellij.execution.process.ProcessOutput

/**
 * A [MirrordEnvironment] that records what it was asked to do, so the resolution chain and the
 * spec builder can be tested with no IDE, no cluster and no container.
 */
class FakeMirrordEnvironment(
    override val name: String = "fake",
    override val isLocal: Boolean = true,
    private val platform: MirrordTargetPlatform = MirrordTargetPlatform(MirrordTargetOs.LINUX, MirrordTargetArch.X86_64)
) : MirrordEnvironment {

    val resolved = mutableListOf<HostPath>()
    val provided = mutableListOf<HostPath>()
    val spawned = mutableListOf<MirrordProcessSpec>()

    override fun platform(): MirrordTargetPlatform = platform

    override fun resolve(path: HostPath): TargetPath {
        resolved += path
        return TargetPath("/target${path.path}")
    }

    override fun provide(path: HostPath, name: String, onCopy: (Long) -> Unit): TargetPath {
        provided += path
        return TargetPath("/target/provided/$name")
    }

    override fun spawn(spec: MirrordProcessSpec): Process {
        spawned += spec
        throw UnsupportedOperationException("FakeMirrordEnvironment does not start real processes")
    }

    override fun probe(executable: TargetPath, args: List<String>, timeoutMillis: Long): ProcessOutput =
        ProcessOutput(0).apply { appendStdout("mirrord 3.247.0") }
}

package com.metalbear.mirrord.bifrost

import java.nio.file.Path
import java.nio.file.Paths

/**
 * A path as the IDE host sees it.
 *
 * Valid for `java.nio`, the VFS, and `PathManager`. **Never hand one of these to the mirrord
 * CLI.** Under a dev container the CLI runs somewhere the host filesystem does not exist, so a
 * host path either names the wrong file or no file at all.
 *
 * Use [MirrordEnvironment.resolve] to cross into [TargetPath].
 */
data class HostPath(val path: Path) {
    override fun toString(): String = path.toString()

    companion object {
        fun of(path: String): HostPath = HostPath(Paths.get(path))
    }
}

/**
 * A path as the *target* environment sees it: inside the container, inside the WSL distribution,
 * or on the host when the target is local.
 *
 * This is what the mirrord CLI receives, as an argument, an environment variable, or a working
 * directory. Separate from [HostPath] so that mixing the two is a compile error.
 */
data class TargetPath(val value: String) {
    override fun toString(): String = value
}

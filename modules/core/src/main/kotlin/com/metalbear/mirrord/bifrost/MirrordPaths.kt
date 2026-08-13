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
 * or on the host when the target happens to be local.
 *
 * This is what the mirrord CLI receives — as an argument, as an environment variable, or as a
 * working directory.
 *
 * [HostPath] and [TargetPath] are separate types on purpose. They were both `String` until
 * COR-1385, where a host path reaching the CLI produced no error at all: mirrord simply ignored
 * a config it could not find, fell back to defaults, and reported zero connected layers for four
 * months. Mixing them is now a compile error rather than a silent runtime one.
 */
data class TargetPath(val value: String) {
    override fun toString(): String = value
}

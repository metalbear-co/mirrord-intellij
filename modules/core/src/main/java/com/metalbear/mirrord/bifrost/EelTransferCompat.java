package com.metalbear.mirrord.bifrost;

import com.intellij.platform.eel.provider.utils.EelPathUtils;

import java.nio.file.Path;

/**
 * Calls {@code transferLocalContentToRemote} in a way that survives the 2026.1 -> 2026.2 API move.
 *
 * <p>The platform declares that function with default arguments, one of which is a file-attribute
 * strategy. That strategy type is {@code EelPathUtils.FileTransferAttributesStrategy} in 2026.1 but
 * a top-level {@code EelFileTransferAttributesStrategy} in 2026.2. Its name appears in the method
 * descriptor, so a plugin compiled against one build fails on the other: running a 2026.1 build on
 * 2026.2 raises {@code ClassNotFoundException} while staging the mirrord CLI into a dev container.
 *
 * <p>Kotlin cannot avoid it: a Kotlin call site always compiles to the synthetic
 * {@code transferLocalContentToRemote$default} bridge, whose signature carries the strategy type
 * even when the argument is omitted. Java has no such bridge and binds to the real two-argument
 * overload, which is byte-identical in both builds. That is the only reason this file is Java.
 *
 * <p>Verify with {@code javap -c} after changing this: the emitted call must be
 * {@code transferLocalContentToRemote:(Ljava/nio/file/Path;L...$TransferTarget;)Ljava/nio/file/Path;}
 * with no third parameter.
 */
final class EelTransferCompat {

    private EelTransferCompat() {
    }

    static Path transferLocalContentToRemote(Path source, EelPathUtils.TransferTarget target) {
        return EelPathUtils.transferLocalContentToRemote(source, target);
    }
}

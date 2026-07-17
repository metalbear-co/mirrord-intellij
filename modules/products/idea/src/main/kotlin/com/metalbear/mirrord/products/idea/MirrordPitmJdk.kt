package com.metalbear.mirrord.products.idea

import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.impl.ProjectJdkImpl
import com.intellij.openapi.util.io.FileUtil
import com.metalbear.mirrord.MirrordLogger
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Creates a fake JDK whose `bin/java.exe` is a copy of `mirrord.exe`, and an
 * [Sdk] whose `homePath` points at it.
 *
 * IntelliJ invokes `<sdk.homePath>/bin/java.exe` for Java run configurations.
 * With this fake JDK, it runs `mirrord.exe` instead. mirrord's `run_as_java_launcher`
 * matches `argv[0]`'s basename against `java.exe`, reads [REAL_JAVA_ENV] for the
 * real `java.exe` path, and executes the pitm flow.
 *
 * CLI source: https://github.com/metalbear-co/mirrord/blob/main/mirrord/cli/src/pitm.rs
 * Introduced in: https://github.com/metalbear-co/mirrord/pull/4191
 *
 * The fake JDK is at `<user.home>/.mirrord/binaries/idea/`. The `bin/java.exe`
 * is re-copied from the resolved `mirrord.exe` on every call to [wrap].
 *
 * Windows-native only. Caller must gate on [com.metalbear.mirrord.isWinNative].
 */
object MirrordPitmJdk {

    const val REAL_JAVA_ENV = "MIRRORD_PITM_REAL_JAVA"

    private val fakeJdkDir: File =
        File(System.getProperty("user.home"), ".mirrord/binaries/idea")

    private val fakeJavaExe: File = File(fakeJdkDir, "bin/java.exe")

    /**
     * [ProjectJdkImpl]'s constructor registers the instance with the application's
     * `VirtualFilePointerManager`, which outlives every run, so instances are
     * reused instead of built per launch.
     */
    private val fakeSdkCache = ConcurrentHashMap<String, Sdk>()

    /**
     * Copies [mirrordExe] to the fake JDK's `bin/java.exe` and returns an [Sdk]
     * that reports the fake directory as its home path.
     *
     * The copy is skipped when the fake `java.exe` already exists and has the
     * same size and MD5 as the source — this avoids rewriting on every run
     * when the binary hasn't changed, while still picking up auto-updates.
     *
     * @return the fake [Sdk], or `null` if it could not be prepared (e.g.
     *         `realJdk.homePath` is null, source `mirrordExe` doesn't exist, or
     *         the copy failed). The caller then falls back to the non-pitm path.
     */
    fun wrap(realJdk: Sdk, mirrordExe: File): Sdk? {
        val realHome = realJdk.homePath ?: run {
            MirrordLogger.logger.warn("MirrordPitmJdk: real JDK home is null, cannot wrap")
            return null
        }
        val realJavaExe = File(realHome, "bin/java.exe")
        if (!realJavaExe.exists()) {
            MirrordLogger.logger.warn("MirrordPitmJdk: real java.exe not found at ${realJavaExe.absolutePath}")
            return null
        }

        if (!mirrordExe.isFile) {
            MirrordLogger.logger.warn("MirrordPitmJdk: mirrord.exe not found at ${mirrordExe.absolutePath}")
            return null
        }

        try {
            fakeJavaExe.parentFile.mkdirs()
            val dstExisted = fakeJavaExe.exists()
            val dstSizeBefore = if (dstExisted) fakeJavaExe.length() else -1L
            if (needsCopy(mirrordExe, fakeJavaExe)) {
                Files.copy(mirrordExe.toPath(), fakeJavaExe.toPath(), StandardCopyOption.REPLACE_EXISTING)
                MirrordLogger.logger.info(
                    "MirrordPitmJdk: copied ${mirrordExe.absolutePath} (size=${mirrordExe.length()}) → " +
                        "${fakeJavaExe.absolutePath} (existed=$dstExisted prevSize=$dstSizeBefore)"
                )
            } else {
                MirrordLogger.logger.info(
                    "MirrordPitmJdk: fake java.exe up-to-date at ${fakeJavaExe.absolutePath} (size=$dstSizeBefore), skipping copy"
                )
            }
        } catch (e: Exception) {
            MirrordLogger.logger.error(
                "MirrordPitmJdk: failed to copy mirrord.exe (${mirrordExe.absolutePath}) into fake JDK " +
                    "(${fakeJavaExe.absolutePath}): ${e.message}",
                e
            )
            return null
        }

        MirrordLogger.logger.info(
            "MirrordPitmJdk: wrapping Sdk ${realJdk.name}, " +
                "realHome=$realHome, fakeHome=${fakeJdkDir.absolutePath}"
        )

        return fakeSdkFor(realJdk)
    }

    /**
     * Builds the fake [Sdk] with the platform's own [ProjectJdkImpl] rather than
     * implementing [Sdk] — the interface is `@ApiStatus.NonExtendable`, so a
     * hand-rolled implementation fails JetBrains' Plugin Verifier.
     *
     * [realJdk]'s `sdkType` is carried over because `JdkCommandLineSetup` refuses
     * to launch anything whose type isn't a `JavaSdkType`, and its `versionString`
     * because the debugger's async-stack-traces agent and the Java 18+ console
     * encoding flags are skipped without one.
     */
    private fun fakeSdkFor(realJdk: Sdk): Sdk {
        val key = "${realJdk.sdkType.name} ${realJdk.name} ${realJdk.versionString}"
        return fakeSdkCache.computeIfAbsent(key) {
            ProjectJdkImpl(
                realJdk.name,
                realJdk.sdkType,
                FileUtil.toSystemIndependentName(fakeJdkDir.absolutePath),
                realJdk.versionString
            )
        }
    }

    private fun needsCopy(src: File, dst: File): Boolean {
        if (!dst.exists()) return true
        if (src.length() != dst.length()) return true
        return !md5(src).contentEquals(md5(dst))
    }

    private fun md5(file: File): ByteArray {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest()
    }
}

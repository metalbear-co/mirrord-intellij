package com.metalbear.mirrord.products.idea

import com.intellij.openapi.projectRoots.Sdk
import com.metalbear.mirrord.MirrordLogger
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Creates a fake JDK whose `bin/java.exe` is a copy of `mirrord.exe`, and wraps
 * a real [Sdk] so its `homePath` points at the fake directory.
 *
 * IntelliJ invokes `<sdk.homePath>/bin/java.exe` for Java run configurations.
 * With this fake JDK, it runs `mirrord.exe` instead. mirrord's `run_as_java_launcher`
 * (see `pitm.rs`) detects `argv[0]` ends with `java.exe`, reads [REAL_JAVA_ENV]
 * for the real `java.exe` path, and executes the pitm flow.
 *
 * The fake JDK is at `<user.home>/.mirrord/binaries/idea/`. The `bin/java.exe`
 * is re-copied from the resolved `mirrord.exe` on every call to [wrap].
 *
 * Windows-native only. Caller must gate on `SystemInfo.isWindows && wsl == null`.
 */
object MirrordPitmJdk {

    const val REAL_JAVA_ENV = "MIRRORD_PITM_REAL_JAVA"

    private val fakeJdkDir: File =
        File(System.getProperty("user.home"), ".mirrord/binaries/idea")

    private val fakeJavaExe: File = File(fakeJdkDir, "bin/java.exe")

    /**
     * Copies [mirrordExe] to the fake JDK's `bin/java.exe` and returns a
     * delegating [Sdk] that reports the fake directory as its home path. All
     * other [Sdk] methods pass through to [realJdk].
     *
     * The copy is skipped when the fake `java.exe` already exists and has the
     * same size and MD5 as the source — this avoids rewriting on every run
     * when the binary hasn't changed, while still picking up auto-updates.
     *
     * @return the wrapping [Sdk], or `null` if the fake could not be prepared
     *         (e.g. `realJdk.homePath` is null, source `mirrordExe` doesn't
     *         exist, or the copy failed).
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
            if (needsCopy(mirrordExe, fakeJavaExe)) {
                Files.copy(mirrordExe.toPath(), fakeJavaExe.toPath(), StandardCopyOption.REPLACE_EXISTING)
                MirrordLogger.logger.info(
                    "MirrordPitmJdk: copied ${mirrordExe.absolutePath} → ${fakeJavaExe.absolutePath}"
                )
            } else {
                MirrordLogger.logger.info(
                    "MirrordPitmJdk: fake java.exe up-to-date at ${fakeJavaExe.absolutePath}, skipping copy"
                )
            }
        } catch (e: Exception) {
            MirrordLogger.logger.error("MirrordPitmJdk: failed to copy mirrord.exe into fake JDK", e)
            return null
        }

        MirrordLogger.logger.info(
            "MirrordPitmJdk: wrapping Sdk ${realJdk.name}, " +
                "realHome=$realHome, fakeHome=${fakeJdkDir.absolutePath}"
        )

        return object : Sdk by realJdk {
            override fun getHomePath(): String = fakeJdkDir.absolutePath
            override fun getHomeDirectory() = null
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

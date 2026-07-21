package com.metalbear.mirrord.products.idea

import com.intellij.execution.configurations.JavaParameters
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.util.SystemInfo
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/**
 * Drives the real `JavaParameters` → `GeneralCommandLine` conversion, so it proves
 * the platform actually launches the fake `java.exe` rather than re-asserting what
 * [MirrordPitmJdk] already returned.
 *
 * Windows-native only, like the pitm path itself.
 */
class MirrordPitmJdkTest : BasePlatformTestCase() {
    private fun realJdk() =
        JavaSdk.getInstance().createJdk(
            "mirrord-test-real",
            System.getProperty("java.home"),
            false,
        )

    /** Stand-in for mirrord.exe. [MirrordPitmJdk.wrap] only copies its bytes, never runs it. */
    private fun fakeMirrordExe(): File =
        File.createTempFile("mirrord-test-", ".exe").apply {
            writeBytes(ByteArray(2048) { 0x4D })
            deleteOnExit()
        }

    fun testWrappedSdkMakesThePlatformLaunchFakeJavaExe() {
        if (!SystemInfo.isWindows) return

        val realJdk = realJdk()
        val mirrordExe = fakeMirrordExe()

        val wrapped = MirrordPitmJdk.wrap(realJdk, mirrordExe)
        assertNotNull("wrap() returned null; fake JDK could not be prepared", wrapped)
        wrapped!!

        val fakeJavaExe = File(wrapped.homePath!!, "bin/java.exe")
        assertTrue("fake bin/java.exe missing", fakeJavaExe.isFile)
        assertEquals("fake java.exe is not the mirrord binary", mirrordExe.length(), fakeJavaExe.length())

        // JdkCommandLineSetup rejects a non-JavaSdkType, and skips the debugger's
        // async-stack-traces agent when the version is unknown.
        assertEquals(realJdk.sdkType, wrapped.sdkType)
        assertEquals(realJdk.versionString, wrapped.versionString)

        val commandLine =
            JavaParameters()
                .apply {
                    jdk = wrapped
                    mainClass = "com.example.Main"
                    classPath.add(File(System.getProperty("java.home"), "lib").absolutePath)
                }.toCommandLine()

        assertEquals(
            "platform did not launch the fake java.exe",
            fakeJavaExe.canonicalPath,
            File(commandLine.exePath).canonicalPath,
        )
    }

    /** The delegating object it replaced returned null here, which makes getJdkPath() throw. */
    fun testWrappedSdkExposesAHomeDirectory() {
        if (!SystemInfo.isWindows) return

        val wrapped = MirrordPitmJdk.wrap(realJdk(), fakeMirrordExe())
        assertNotNull(wrapped)
        assertNotNull("homePath must not be null; getConvertedHomePath asserts on it", wrapped!!.homePath)
    }

    /** ProjectJdkImpl registers itself with VirtualFilePointerManager, so instances are reused. */
    fun testRepeatedWrapsReuseOneSdkInstance() {
        if (!SystemInfo.isWindows) return

        val realJdk = realJdk()
        val first = MirrordPitmJdk.wrap(realJdk, fakeMirrordExe())
        val second = MirrordPitmJdk.wrap(realJdk, fakeMirrordExe())

        assertNotNull(first)
        assertSame("each wrap() leaked a new ProjectJdkImpl", first, second)
    }
}

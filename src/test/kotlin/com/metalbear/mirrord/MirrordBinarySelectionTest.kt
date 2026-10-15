package com.metalbear.mirrord

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.metalbear.mirrord.bifrost.MirrordEnvironments
import java.io.File
import java.util.concurrent.TimeUnit

class MirrordBinarySelectionTest : BasePlatformTestCase() {
    fun testAnInvalidCustomPathDoesNotSettleAutomaticSelection() {
        val settings = MirrordSettingsState.instance.mirrordState
        val oldPath = settings.mirrordBinaryPath
        val oldAutoUpdate = settings.autoUpdate
        try {
            settings.mirrordBinaryPath = File(myFixture.tempDirFixture.tempDirPath, "missing-mirrord").path
            settings.autoUpdate = true
            val manager = MirrordBinaryManager()

            val selection = ApplicationManager.getApplication().executeOnPooledThread<MirrordBinarySelection> {
                manager.findBinary(MirrordEnvironments.forProject(project))
            }.get(30, TimeUnit.SECONDS)

            assertFalse("an invalid custom path must not settle automatic selection", selection.isSettled)
        } finally {
            settings.mirrordBinaryPath = oldPath
            settings.autoUpdate = oldAutoUpdate
        }
    }
}

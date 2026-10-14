package com.metalbear.mirrord

import com.intellij.execution.RunManager
import com.intellij.execution.application.ApplicationConfigurationType
import com.intellij.execution.impl.RunManagerImpl
import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.jsonSchema.ide.JsonSchemaService
import com.jetbrains.jsonSchema.impl.JsonSchemaServiceImpl
import com.jetbrains.jsonSchema.impl.inspections.JsonSchemaComplianceInspection
import java.io.File
import java.util.Collections

/**
 * Checks that the IDE checks mirrord config files with the schema that the mirrord binary prints.
 *
 * The binary is a shell script, so these tests do not run on Windows.
 */
class MirrordSchemaFileProviderTest : BasePlatformTestCase() {
    private val settings
        get() = MirrordSettingsState.instance.mirrordState

    private lateinit var settingsBefore: MirrordSettingsState.MirrordState

    /** A new project for each test, so that no test sees the schema of a different test. */
    override fun getProjectDescriptor() = object : LightProjectDescriptor() {}

    override fun setUp() {
        super.setUp()
        settingsBefore = MirrordSettingsState.MirrordState().apply {
            mirrordBinaryPath = settings.mirrordBinaryPath
            autoUpdate = settings.autoUpdate
            mirrordVersion = settings.mirrordVersion
        }
        myFixture.enableInspections(JsonSchemaComplianceInspection::class.java)
    }

    override fun tearDown() {
        try {
            settings.mirrordBinaryPath = settingsBefore.mirrordBinaryPath
            settings.autoUpdate = settingsBefore.autoUpdate
            settings.mirrordVersion = settingsBefore.mirrordVersion
        } finally {
            super.tearDown()
        }
    }

    /**
     * A fake mirrord binary that prints [schema] for `mirrord print-schema`.
     * It creates [startedMarker] first. With [waitForRelease], it then waits for [releaseMarker].
     */
    private fun fakeBinary(schema: String, waitForRelease: Boolean = false): File = File.createTempFile("mirrord-", "").apply {
        writeText(
            """
            |#!/bin/sh
            |case "${'$'}1" in
            |  --version) echo "mirrord 3.272.0" ;;
            |  print-schema)
            |    touch "${'$'}0.started"
            |    if $waitForRelease; then
            |      while [ ! -f "${'$'}0.release" ]; do sleep 0.01; done
            |    fi
            |    cat <<'EOF'
            |$schema
            |EOF
            |  ;;
            |  *) exit 2 ;;
            |esac
            |
            """.trimMargin()
        )
        setExecutable(true)
        deleteOnExit()
        startedMarker(this).deleteOnExit()
        releaseMarker(this).deleteOnExit()
    }

    /** The file that [fakeBinary] creates when it starts to print its schema. */
    private fun startedMarker(binary: File) = File("${binary.path}.started")

    /** Creating this file lets a waiting [fakeBinary] finish printing its schema. */
    private fun releaseMarker(binary: File) = File("${binary.path}.release")

    /** A schema that allows only the [property] property. */
    private fun schemaAllowing(property: String) =
        """{"type": "object", "properties": {"$property": {"type": "boolean"}}, "additionalProperties": false}"""

    /** Settings with which the plugin has no mirrord binary, and cannot download one. */
    private fun useNoBinary() {
        settings.mirrordBinaryPath = ""
        settings.autoUpdate = false
        // Higher than any real version. With `CI_BUILD_PLUGIN=true`, a binary in `PATH` matches every
        // version that is not higher than its own.
        settings.mirrordVersion = "999.0.0"
    }

    private fun schemaFilesFor(file: VirtualFile): Collection<VirtualFile> =
        ApplicationManager.getApplication().runReadAction<Collection<VirtualFile>> {
            JsonSchemaService.Impl.get(project).getSchemaFilesForFile(file)
        }

    /** Waits until the IDE uses a schema for [file] whose text contains [text]. */
    private fun waitForSchemaWith(file: VirtualFile, text: String) {
        PlatformTestUtil.waitWithEventsDispatching("the IDE did not get the schema from the binary", {
            schemaFilesFor(file).any { VfsUtilCore.loadText(it).contains(text) }
        }, 30)
    }

    /** Starts the first load of the schema, and waits until it ends. */
    private fun loadSchema() {
        val schema = project.service<MirrordConfigSchema>()
        schema.markNeeded()
        PlatformTestUtil.waitWithEventsDispatching("the schema did not load", { schema.isLoaded }, 30)
    }

    /** Collects the text of the notifications that the plugin shows in this project. */
    private fun collectNotifications(): List<String> {
        val notifications = mutableListOf<String>()
        project.messageBus.connect(testRootDisposable).subscribe(
            Notifications.TOPIC,
            object : Notifications {
                override fun notify(notification: Notification) {
                    notifications += notification.content
                }
            }
        )
        return notifications
    }

    fun testConfigFileIsCheckedWithTheSchemaOfTheBinary() {
        if (SystemInfo.isWindows) return
        settings.mirrordBinaryPath = fakeBinary(schemaAllowing("from_binary")).path

        val config = myFixture.configureByText(
            "mirrord.json",
            """{"from_binary": true, <warning descr="Property 'unknown' is not allowed">"unknown"</warning>: true}"""
        )
        waitForSchemaWith(config.virtualFile, "from_binary")

        myFixture.checkHighlighting()
    }

    fun testOpenConfigFileGetsTheSchemaOfANewBinary() {
        if (SystemInfo.isWindows) return
        settings.mirrordBinaryPath = fakeBinary(schemaAllowing("from_old_binary")).path

        val config = myFixture.configureByText(
            "mirrord.json",
            """{"from_new_binary": true, <warning descr="Property 'unknown' is not allowed">"unknown"</warning>: true}"""
        )
        waitForSchemaWith(config.virtualFile, "from_old_binary")

        // What the settings page does when the user changes the binary path.
        settings.mirrordBinaryPath = fakeBinary(schemaAllowing("from_new_binary")).path
        MirrordBinaryListener.publish()
        waitForSchemaWith(config.virtualFile, "from_new_binary")

        myFixture.checkHighlighting()
    }

    fun testTheSchemaOfAReplacedBinaryNeverReachesTheIde() {
        if (SystemInfo.isWindows) return
        val oldBinary = fakeBinary(schemaAllowing("from_old_binary"), waitForRelease = true)
        val middleBinary = fakeBinary(schemaAllowing("from_middle_binary"))
        val newBinary = fakeBinary(schemaAllowing("from_new_binary"))
        settings.mirrordBinaryPath = oldBinary.path
        val config = myFixture.configureByText("mirrord.json", "{}")
        // The schemas that the IDE gets. Each new schema comes with a reset of the JSON schemas.
        val schemas = Collections.synchronizedList(mutableListOf<String>())
        (JsonSchemaService.Impl.get(project) as JsonSchemaServiceImpl).registerResetAction {
            schemas += schemaFilesFor(config.virtualFile).joinToString { VfsUtilCore.loadText(it) }
        }

        try {
            project.service<MirrordConfigSchema>().markNeeded()
            PlatformTestUtil.waitWithEventsDispatching("the old binary did not start to print its schema", {
                startedMarker(oldBinary).exists()
            }, 30)
            settings.mirrordBinaryPath = middleBinary.path
            MirrordBinaryListener.publish()
            settings.mirrordBinaryPath = newBinary.path
            MirrordBinaryListener.publish()

            assertFalse("a second lookup started before the first ended", startedMarker(newBinary).exists())
        } finally {
            releaseMarker(oldBinary).createNewFile()
        }
        waitForSchemaWith(config.virtualFile, "from_new_binary")

        assertFalse("the worker loaded a binary that was already replaced", startedMarker(middleBinary).exists())
        assertTrue("the IDE got the schema of the old binary: $schemas", schemas.none { it.contains("from_old_binary") })
    }

    fun testSelectingAnotherRunConfigurationReloadsTheSchema() {
        if (SystemInfo.isWindows) return
        val manager = RunManager.getInstance(project)
        val factory = ApplicationConfigurationType.getInstance().configurationFactories.single()
        val first = manager.createConfiguration("first", factory)
        val second = manager.createConfiguration("second", factory)
        manager.addConfiguration(first)
        manager.addConfiguration(second)
        manager.selectedConfiguration = first
        val binary = fakeBinary(schemaAllowing("before_selection"))
        settings.mirrordBinaryPath = binary.path
        val config = myFixture.configureByText("mirrord.json", "{}")
        waitForSchemaWith(config.virtualFile, "before_selection")

        // Changing the selected run target must reload even without a binary-change event.
        binary.writeText(fakeBinary(schemaAllowing("after_selection")).readText())
        manager.selectedConfiguration = second

        waitForSchemaWith(config.virtualFile, "after_selection")
    }

    fun testEditingTheSelectedRunConfigurationReloadsTheSchema() {
        if (SystemInfo.isWindows) return
        val manager = RunManager.getInstance(project)
        val factory = ApplicationConfigurationType.getInstance().configurationFactories.single()
        val selected = manager.createConfiguration("selected", factory)
        manager.addConfiguration(selected)
        manager.selectedConfiguration = selected
        val binary = fakeBinary(schemaAllowing("before_edit"))
        settings.mirrordBinaryPath = binary.path
        val config = myFixture.configureByText("mirrord.json", "{}")
        waitForSchemaWith(config.virtualFile, "before_edit")

        binary.writeText(fakeBinary(schemaAllowing("after_edit")).readText())
        selected.name = "edited"
        (manager as RunManagerImpl).fireRunConfigurationChanged(selected)

        waitForSchemaWith(config.virtualFile, "after_edit")
    }

    fun testLoadingTheSchemaDoesNotDownloadTheBinary() {
        useNoBinary()
        val notifications = collectNotifications()

        loadSchema()
        // The plugin shows its notifications with `invokeLater`.
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        // A download of the missing version would fail, and show a notification.
        assertEmpty(notifications)
    }

    fun testLoadingTheSchemaDoesNotWarnAboutTheCustomBinaryPath() {
        useNoBinary()
        settings.mirrordBinaryPath = "/does/not/exist/mirrord"
        val notifications = collectNotifications()

        loadSchema()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        // A run warns about this path. Opening a config file must not.
        assertEmpty(notifications)
    }

    fun testConfigFileHasNoSchemaFromGitHubWhenThereIsNoBinary() {
        useNoBinary()
        // The mirrord entry of the SchemaStore catalog, https://www.schemastore.org/api/json/catalog.json.
        val catalog = LightVirtualFile(
            "catalog.json",
            """
            {"schemas": [{"name": "mirrord config", "description": "mirrord",
              "fileMatch": ["*.mirrord.json"],
              "url": "https://raw.githubusercontent.com/metalbear-co/mirrord/main/mirrord-schema.json"}]}
            """.trimIndent()
        )
        (JsonSchemaService.Impl.get(project) as JsonSchemaServiceImpl).catalogManager
            .registerTestSchemaStoreFile(catalog, testRootDisposable)

        val config = myFixture.configureByText("dev.mirrord.json", "{}")
        loadSchema()

        // A file from the network has no text yet, so the test shows its URL instead.
        val schemas = schemaFilesFor(config.virtualFile).map { if (it is LightVirtualFile) it.content.toString() else it.url }
        assertEquals(listOf("{}"), schemas)
    }
}

package com.metalbear.mirrord

import com.intellij.ide.BrowserUtil
import com.intellij.ide.DataManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.*
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.util.indexing.*
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor
import java.util.*
import javax.swing.JComponent

const val DISCORD_URL = "https://discord.gg/metalbear"
const val MIRRORD_FOR_TEAMS_URL = "https://app.metalbear.co/"

fun VirtualFile.relativePath(project: Project): String {
    return calcRelativeToProjectPath(this, project, includeFilePath = true, keepModuleAlwaysOnTheLeft = true)
}

class MirrordDropDown : ComboBoxAction(), DumbAware {

    private class ShowActiveConfigAction(val config: VirtualFile, project: Project) :
        AnAction("Active Config: ${config.relativePath(project)}") {
        override fun actionPerformed(e: AnActionEvent) {
            val service = e.project?.service<MirrordProjectService>() ?: return
            FileEditorManager.getInstance(service.project).openFile(config, true)
        }
    }

    private class ToggleEnabledByDefault : AnAction("Enable/ Disable mirrord by Default") {
        override fun actionPerformed(e: AnActionEvent) {
            val newValue = !MirrordSettingsState.instance.mirrordState.enabledByDefault
            MirrordSettingsState.instance.mirrordState.enabledByDefault = newValue
            // popup containing new value of enabledByDefault
            val service = e.project?.service<MirrordProjectService>() ?: return
            val notification = if (newValue) {
                service.notifier.notification(
                    "mirrord set to be enabled by default",
                    NotificationType.IDE_UPDATE
                )
            } else {
                service.notifier.notification(
                    "mirrord set to be disabled by default",
                    NotificationType.IDE_UPDATE
                )
            }

            notification.fire()

            return
        }
    }

    private class SelectActiveConfigAction : AnAction("Select Active Config") {
        override fun actionPerformed(e: AnActionEvent) {
            val service = e.project?.service<MirrordProjectService>() ?: return

            val fileManager = VirtualFileManager.getInstance()
            val projectLocator = ProjectLocator.getInstance()
            val configs = FileBasedIndex
                .getInstance()
                .getAllKeys(MirrordConfigIndex.key, service.project)
                .mapNotNull { fileManager.findFileByUrl(it) }
                .filter { !it.isDirectory }
                .filter { projectLocator.getProjectsForFile(it).contains(service.project) }
                .associateBy { it.relativePath(service.project) }

            val selection = MirrordConfigDialog(
                "Change mirrord active configuration",
                configs.keys.toList().sorted()
            ).show() ?: return

            service.activeConfig = selection.option?.let { configs[it] }
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = e.project?.let { !DumbService.isDumb(it) } ?: false
            super.update(e)
        }

        override fun getActionUpdateThread() = ActionUpdateThread.BGT
    }

    private class SettingsAction : AnAction("Settings") {
        override fun actionPerformed(e: AnActionEvent) {
            val service = e.project?.service<MirrordProjectService>() ?: return

            val configs: MutableMap<String, () -> Unit> = mutableMapOf()
            service.activeConfig?.let {
                configs["(active) %s".format(it.relativePath(service.project))] = {
                    FileEditorManager.getInstance(service.project).openFile(it, true)
                }
            }

            val defaultConfig = service.configApi.getDefaultConfig()
            if (defaultConfig == null) {
                configs["(create default)"] = {
                    WriteAction.compute<_, InvalidProjectException> {
                        val config = service.configApi.createDefaultConfig()
                        FileEditorManager.getInstance(service.project).openFile(config, true)
                    }
                }
            } else {
                configs["(default) %s".format(defaultConfig.relativePath(service.project))] = {
                    FileEditorManager.getInstance(service.project).openFile(defaultConfig, true)
                }
            }

            val selected = configs
                .keys
                .toList()
                .sorted()
                .ifEmpty { null }
                ?.let {
                    it.singleOrNull() ?: MirrordConfigDialog("Edit mirrord configuration", it).show()?.option
                }

            selected?.let {
                try {
                    configs[it]?.invoke()
                } catch (ex: InvalidProjectException) {
                    service.notifier.notifyRichError(ex.message)
                }
            }
        }
    }

    private class NavigateToMirrodForTeamsIntroAction : AnAction("Try It Now") {
        override fun actionPerformed(e: AnActionEvent) {
            BrowserUtil.browse(MIRRORD_FOR_TEAMS_URL)
        }
    }

    private class DiscordAction : AnAction("Get Help on Discord") {
        override fun actionPerformed(e: AnActionEvent) {
            BrowserUtil.browse(DISCORD_URL)
        }
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun createPopupActionGroup(button: JComponent, dataContext: DataContext): DefaultActionGroup {
        val project = dataContext.getData(CommonDataKeys.PROJECT) ?: throw Error("mirrord requires an open project")
        val service = project.service<MirrordProjectService>()

        return DefaultActionGroup().apply {
            addSeparator("Configuration")
            service.activeConfig?.let { add(ShowActiveConfigAction(it, project)) }
            add(SelectActiveConfigAction())
            add(SettingsAction())
            add(ToggleEnabledByDefault())

            if (!MirrordSettingsState.instance.mirrordState.operatorUsed) {
                addSeparator("mirrord for Teams")
                add(NavigateToMirrodForTeamsIntroAction())
            }

            addSeparator("Help")
            add(DiscordAction())
        }
    }

    @Deprecated(
        "Deprecated in Java",
        ReplaceWith(
            "createPopupActionGroup(button, DataManager.getInstance().getDataContext(button))",
            "com.intellij.ide.DataManager"
        )
    )
    override fun createPopupActionGroup(button: JComponent): DefaultActionGroup {
        return createPopupActionGroup(button, DataManager.getInstance().getDataContext(button))
    }

    override fun update(e: AnActionEvent) {
        val projectOpen = e.project != null

        e.presentation.isVisible = true
        e.presentation.isEnabled = projectOpen
        e.presentation.description =
            if (projectOpen) "Options for mirrord plugin" else "Plugin requires an open project"

        super.update(e)
    }
}

/**
 * An index for mirrord config files.
 * Indexes files with names ending with `mirrord.json`.
 */
class MirrordConfigIndex : ScalarIndexExtension<String>() {

    companion object {
        val key = ID.create<String, Void>("mirrordConfig")
    }

    override fun getName(): ID<String, Void> {
        return key
    }

    override fun getIndexer(): DataIndexer<String, Void, FileContent> {
        return DataIndexer {
            Collections.singletonMap<String, Void>(it.file.url, null)
        }
    }

    override fun getKeyDescriptor(): KeyDescriptor<String> {
        return EnumeratorStringDescriptor.INSTANCE
    }

    override fun getVersion(): Int {
        return 0
    }

    override fun getInputFilter(): FileBasedIndex.InputFilter {
        return FileBasedIndex.InputFilter {
            it.isInLocalFileSystem && !it.isDirectory && MirrordConfigAPI.isConfigFilePath(it)
        }
    }

    override fun dependsOnFileContent(): Boolean {
        return false
    }
}

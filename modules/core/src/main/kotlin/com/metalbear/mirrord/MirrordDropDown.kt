package com.metalbear.mirrord

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.*
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.text.StringUtil.ELLIPSIS
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.util.indexing.*
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor
import com.intellij.util.ui.JBDimension
import java.util.*
import javax.swing.JComponent

const val DISCORD_URL = "https://discord.gg/metalbear"
const val MIRRORD_FOR_TEAMS_URL = "https://app.metalbear.co/?utm_medium=intellij&utm_source=ui_action"
const val DOCS_URL = "https://mirrord.dev/docs/using-mirrord/intellij-plugin/?utm_medium=intellij&utm_source=ui_action"

/**
 * Copied from internal [com.intellij.execution.ui.TogglePopupAction].
 */
abstract class TogglePopupAction : ToggleAction() {
    override fun isSelected(e: AnActionEvent): Boolean {
        return Toggleable.isSelected(e.presentation)
    }

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        if (!state) return
        val component = e.inputEvent?.component as? JComponent ?: return
        val popup = createPopup(e) ?: return
        popup.showUnderneathOf(component)
    }

    private fun createPopup(e: AnActionEvent): JBPopup? {
        val presentation = e.presentation
        val actionGroup = getActionGroup(e) ?: return null
        val disposeCallback = { Toggleable.setSelected(presentation, false) }
        val popup = createPopup(actionGroup, e, disposeCallback)
        popup.setMinimumSize(JBDimension(270, 0))
        return popup
    }

    open fun createPopup(
        actionGroup: ActionGroup,
        e: AnActionEvent,
        disposeCallback: () -> Unit
    ) = JBPopupFactory.getInstance().createActionGroupPopup(
        null,
        actionGroup,
        e.dataContext,
        false,
        false,
        false,
        disposeCallback,
        30,
        null
    )

    abstract fun getActionGroup(e: AnActionEvent): ActionGroup?
}

fun VirtualFile.relativePath(project: Project): String {
    return calcRelativeToProjectPath(this, project, includeFilePath = true, keepModuleAlwaysOnTheLeft = false)
        .substringAfter("$ELLIPSIS/") // trim leading "…/", which is confusing
}

class MirrordDropDown : TogglePopupAction(), DumbAware {
    private class ShowActiveConfigAction(val config: VirtualFile, project: Project) :
        AnAction("Active Config: ${config.relativePath(project)}") {
        override fun actionPerformed(e: AnActionEvent) {
            val service = e.project?.service<MirrordProjectService>() ?: return
            FileEditorManager.getInstance(service.project).openFile(config, true)
        }
    }

    private class UnsetActiveConfigAction : AnAction("Unset Active Config") {
        override fun actionPerformed(e: AnActionEvent) {
            val service = e.project?.service<MirrordProjectService>() ?: return
            service.activeConfig = null
        }
    }

    private class SelectActiveConfigAction : AnAction("Select Active Config") {
        override fun actionPerformed(e: AnActionEvent) {
            val service = e.project?.service<MirrordProjectService>() ?: return

            val fileManager = VirtualFileManager.getInstance()
            val projectLocator = ProjectLocator.getInstance()
            val configs = FileBasedIndex
                .getInstance()
                .getAllKeys(MIRRORD_CONFIG_INDEX_KEY, service.project)
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

    private class DocsAction : AnAction("Documentation") {
        override fun actionPerformed(e: AnActionEvent) {
            BrowserUtil.browse(DOCS_URL)
        }
    }

    override fun getActionGroup(e: AnActionEvent): ActionGroup {
        val project = e.project ?: throw Error("mirrord requires an open project")
        val service = project.service<MirrordProjectService>()

        return DefaultActionGroup().apply {
            addSeparator("Configuration")
            service.activeConfig?.let {
                add(ShowActiveConfigAction(it, project))
                add(UnsetActiveConfigAction())
            }
            add(SelectActiveConfigAction())
            add(SettingsAction())

            if (!MirrordSettingsState.instance.mirrordState.operatorUsed) {
                addSeparator("mirrord for Teams")
                add(NavigateToMirrodForTeamsIntroAction())
            }

            addSeparator("Help")
            add(DocsAction())
            add(DiscordAction())
        }
    }

    override fun displayTextInToolbar(): Boolean = true

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val projectOpen = e.project != null

        e.presentation.isVisible = true
        e.presentation.isEnabled = projectOpen
        e.presentation.description =
            if (projectOpen) "Options for mirrord plugin" else "Plugin requires an open project"

        super.update(e)
    }
}

private val MIRRORD_CONFIG_INDEX_KEY = ID.create<String, Void>("mirrordConfig")

/**
 * An index for mirrord config files.
 * Indexes files with names ending with `mirrord.json`.
 */
class MirrordConfigIndex : ScalarIndexExtension<String>() {

    override fun getName(): ID<String, Void> {
        return MIRRORD_CONFIG_INDEX_KEY
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

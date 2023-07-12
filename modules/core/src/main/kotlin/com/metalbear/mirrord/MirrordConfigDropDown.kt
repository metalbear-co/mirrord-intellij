package com.metalbear.mirrord

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.ProjectLocator
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.util.indexing.*
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor
import java.util.Collections
import javax.swing.JComponent

class MirrordConfigDropDown : ComboBoxAction() {
    private class EnableMirrordAction(val enabled: Boolean) : AnAction(if (enabled) "Enabled" else "Disabled") {
        override fun actionPerformed(e: AnActionEvent) {
            MirrordEnabler.setEnabled(!enabled, e.project)
        }
    }

    private class ShowActiveConfigAction(val config: VirtualFile) : AnAction("Active Config: ${config.presentableUrl}") {
        override fun actionPerformed(e: AnActionEvent) {
            FileEditorManager.getInstance(e.project!!).openFile(config, true)
        }
    }

    private class SelectActiveConfigAction : AnAction("Select Active Config") {
        override fun actionPerformed(e: AnActionEvent) {
            val fileManager = VirtualFileManager.getInstance()
            val projectLocator = ProjectLocator.getInstance()
            val configs = FileBasedIndex
                    .getInstance()
                    .getAllKeys(MirrordConfigIndex.key, e.project!!)
                    .mapNotNull { fileManager.findFileByUrl(it) }
                    .filter { projectLocator.getProjectsForFile(it).isNotEmpty() }
                    .associateBy { it.presentableUrl }

            val selection = MirrordConfigDialog(
                    "Change mirrord active configuration",
                    configs.keys.toList().sorted()
            ).show() ?: return

            selection.option.let {
                if (it.option == null) {
                    MirrordConfigAPI.activeConfigs.remove(e.project!!)
                } else {
                    MirrordConfigAPI.activeConfigs[e.project!!] = it.option
                }
            }
    }

    private class SettingsAction : AnAction("Settings") {
        override fun actionPerformed(e: AnActionEvent) {
            val configs: MutableMap<String, () -> Unit> = mutableMapOf()
            MirrordConfigAPI.activeConfig?.let {
                configs["(active) %s".format(it.presentableUrl)] = {
                    FileEditorManager.getInstance(e.project!!).openFile(it, true)
                }
            }

            val defaultConfig = MirrordConfigAPI.getDefaultConfig(e.project!!)
            if (defaultConfig == null) {
                configs["(create default)"] = {
                    val config = MirrordConfigAPI.createDefaultConfig(e.project!!)
                    FileEditorManager.getInstance(e.project!!).openFile(config, true)
                }
            } else {
                configs["(default) %s".format(defaultConfig.presentableUrl)] = {
                    FileEditorManager.getInstance(e.project!!).openFile(defaultConfig, true)
                }
            }

            val options = configs.keys.toList().sorted()

            val selected = if (options.size == 1) {
                options.first()
            } else if (options.size > 1) {
                MirrordConfigDialog("Edit mirrord configuration", options).show()?.option
            } else {
                null
            }

            selected?.let {
                try {
                    configs[it]?.invoke()
                } catch (ex: MirrordConfigAPI.InvalidProjectException) {
                    MirrordNotifier.errorNotification(ex.message!!, e.project)
                }
            }
        }
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun createPopupActionGroup(button: JComponent, dataContext: DataContext): DefaultActionGroup {
        return DefaultActionGroup().apply {
            add(EnableMirrordAction(MirrordEnabler.enabled))
            addSeparator("Configuration")
            MirrordConfigAPI.activeConfig?.let { add(ShowActiveConfigAction(it)) }
            add(SelectActiveConfigAction())
            add(SettingsAction())
        }
    }

    override fun update(e: AnActionEvent) {
        val projectOpen = e.project != null

        e.presentation.isVisible = true
        e.presentation.isEnabled = projectOpen
        e.presentation.description = if (projectOpen) "Options for mirrord plugin" else "Plugin requires an open project"
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
            it.isInLocalFileSystem && !it.isDirectory && it.path.endsWith("mirrord.json")
        }
    }

    override fun dependsOnFileContent(): Boolean {
        return false
    }
}

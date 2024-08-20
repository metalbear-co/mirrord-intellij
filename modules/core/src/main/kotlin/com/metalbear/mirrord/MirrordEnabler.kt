package com.metalbear.mirrord

import com.intellij.ide.ActivityTracker
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.metalbear.mirrord.MirrordSettingsState.Companion.instance
import icons.MirrordIcons

class MirrordEnabler : ToggleAction(), DumbAware, StartupActivity, StartupActivity.DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent): Boolean {
        return e.project?.service<MirrordProjectService>()?.enabled ?: false
    }

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        val project = e.project ?: throw Error("mirrord requires an open project")
        project.service<MirrordProjectService>().enabled = state
        e.presentation.icon = if (state) MirrordIcons.enabled else MirrordIcons.disabled
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isVisible = true
        e.presentation.isEnabled = e.project != null
        val state = e.project?.service<MirrordProjectService>()?.enabled ?: false
        e.presentation.icon = if (state) MirrordIcons.enabled else MirrordIcons.disabled

        super.update(e)
    }

    override fun runActivity(project: Project) {
        if (instance.mirrordState.enabledByDefault) {
            project.service<MirrordProjectService>().enabled = true
            ActivityTracker.getInstance().inc()
        }
    }
}

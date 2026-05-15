package com.metalbear.mirrord

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import icons.MirrordIcons

class MirrordEnabler : ToggleAction(), DumbAware {
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
}

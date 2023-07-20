package com.metalbear.mirrord

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware

class MirrordEnabler : ToggleAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent): Boolean {
        return e.project?.service<MirrordProjectService>()?.enabled ?: false
    }

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        val project = e.project ?: throw Error("mirrord requires an open project")
        project.service<MirrordProjectService>().enabled = state
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isVisible = true
        e.presentation.isEnabled = e.project != null

        super.update(e)
    }
}

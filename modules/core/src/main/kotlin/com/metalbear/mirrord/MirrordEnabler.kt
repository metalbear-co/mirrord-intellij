package com.metalbear.mirrord

import com.intellij.ide.BrowserUtil
import com.intellij.notification.*
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

class MirrordEnabler : ToggleAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent): Boolean {
        val project = e.project ?: throw Error("mirrord requires an open project")
        return project.service<MirrordProjectService>().enabled
    }

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        val project = e.project ?: throw Error("mirrord requires an open project")
        project.service<MirrordProjectService>().enabled = state
    }
}

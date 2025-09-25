package com.metalbear.mirrord

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.swing.JComponent

@Service(Service.Level.PROJECT)
class MirrordLogsService(private val project: Project) {
    private var consoleView: ConsoleView? = null

    fun createConsoleComponent(): JComponent {
        if (consoleView == null) {
            consoleView = TextConsoleBuilderFactory.getInstance()
                .createBuilder(project)
                .console
        }
        return consoleView!!.component
    }

    fun logMessage(message: String, contentType: ConsoleViewContentType = ConsoleViewContentType.NORMAL_OUTPUT) {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"))
        val timestampedMessage = "[$timestamp] $message"
        ApplicationManager.getApplication().invokeLater {
            consoleView?.print("$timestampedMessage\n", contentType)
        }
    }

    fun logInfo(message: String) {
        logMessage("[INFO] $message", ConsoleViewContentType.NORMAL_OUTPUT)
    }

    fun logWarning(message: String) {
        logMessage("[WARN] $message", ConsoleViewContentType.LOG_WARNING_OUTPUT)
    }

    fun logError(message: String) {
        logMessage("[ERROR] $message", ConsoleViewContentType.LOG_ERROR_OUTPUT)
    }

    fun onMirrordExecutionStart() {
        logInfo("mirrord execution started...")
        showToolWindow()
    }

    fun onMirrordExecutionEnd() {
        logInfo("mirrord execution finished.")
    }

    private fun showToolWindow() {
        ApplicationManager.getApplication().invokeLater {
            val toolWindowManager = ToolWindowManager.getInstance(project)
            val toolWindow = toolWindowManager.getToolWindow("mirrord Logs")
            toolWindow?.let {
                if (!it.isVisible) {
                    it.activate(null)
                }
            }
        }
    }

    fun dispose() {
        consoleView?.dispose()
        consoleView = null
    }
}

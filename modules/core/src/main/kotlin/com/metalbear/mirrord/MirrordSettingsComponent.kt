package com.metalbear.mirrord

import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import java.awt.event.ItemEvent
import javax.swing.JComponent
import javax.swing.JPanel

class MirrordSettingsComponent {
    private val versionCheckEnabled = JBCheckBox("Version check")
    private val notificationsEnabled =
        MirrordSettingsState
            .NotificationId
            .values()
            .associateWith { JBCheckBox(it.presentableName) }

    private val usageBannerEnabled = JBCheckBox("Show usage banner on startup")
    private val enabledOnStartup = JBCheckBox("Enable mirrord on startup")

    private val mirrordVersionLabel = JBLabel("mirrord binary version:")
    private val mirrordVersion =
        with(JBTextField("", 10)) {
            toolTipText = "specify mirrord binary version to use"
            this
        }

    private val mirrordBinaryPathLabel = JBLabel("mirrord binary path:")
    private val mirrordBinaryPath =
        with(JBTextField("", 30)) {
            toolTipText = "absolute path to a mirrord binary (overrides auto-update and the version setting)"
            this
        }

    private val taskTimeoutLabel = JBLabel("mirrord task timeout (minutes):")
    private val taskTimeout =
        with(JBTextField("", 5)) {
            toolTipText = "how long to wait for a mirrord task (e.g. starting the agent) before timing out"
            this
        }

    private val autoUpdate =
        JBCheckBox("Auto update mirrord binary")
            .apply {
                addItemListener { e ->
                    mirrordVersion.isEnabled = e.stateChange != ItemEvent.SELECTED
                }
            }

    private val autoUpdatePanel =
        FormBuilder
            .createFormBuilder()
            .addComponent(autoUpdate)
            .addLabeledComponent(mirrordVersionLabel, mirrordVersion)
            .addLabeledComponent(mirrordBinaryPathLabel, mirrordBinaryPath)
            .addComponentFillVertically(JPanel(), 0)
            .panel

    val panel: JPanel =
        FormBuilder
            .createFormBuilder()
            .addComponent(usageBannerEnabled)
            .addComponent(enabledOnStartup)
            .addComponent(versionCheckEnabled)
            .addLabeledComponent(taskTimeoutLabel, taskTimeout)
            .addSeparator()
            .addComponent(autoUpdatePanel)
            .addSeparator()
            .addComponent(JBLabel("Notify when:"))
            .apply {
                notificationsEnabled.forEach {
                    addComponent(it.value)
                }
            }.addSeparator()
            .addComponentFillVertically(JPanel(), 0)
            .panel

    val preferredFocusedComponent: JComponent
        get() = versionCheckEnabled

    var usageBannerEnabledStatus: Boolean
        get() = usageBannerEnabled.isSelected
        set(value) {
            usageBannerEnabled.isSelected = value
        }

    var enabledOnStartupStatus: Boolean
        get() = enabledOnStartup.isSelected
        set(value) {
            enabledOnStartup.isSelected = value
        }

    var versionCheckEnabledStatus: Boolean
        get() = versionCheckEnabled.isSelected
        set(newStatus) {
            versionCheckEnabled.isSelected = newStatus
        }

    var notificationsDisabledStatus: Set<MirrordSettingsState.NotificationId>
        get() = notificationsEnabled.filter { !it.value.isSelected }.keys
        set(value) =
            notificationsEnabled.forEach {
                it.value.isSelected = !value.contains(it.key)
            }

    var autoUpdateEnabledStatus: Boolean
        get() = autoUpdate.isSelected
        set(value) {
            autoUpdate.isSelected = value
        }

    var mirrordVersionStatus: String
        get() = mirrordVersion.text
        set(value) {
            // to avoid errornous whitespaces
            mirrordVersion.text = value.trim()
        }

    var mirrordBinaryPathStatus: String
        get() = mirrordBinaryPath.text
        set(value) {
            mirrordBinaryPath.text = value.trim()
        }

    /**
     * The configured task timeout in minutes. Falls back to the default if the field is empty
     * or not a positive integer.
     */
    var taskTimeoutMinutesStatus: Int
        get() =
            taskTimeout.text
                .trim()
                .toIntOrNull()
                ?.takeIf { it > 0 } ?: DEFAULT_TASK_TIMEOUT_MINUTES
        set(value) {
            taskTimeout.text = value.toString()
        }
}

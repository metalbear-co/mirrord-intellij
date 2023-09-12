package com.metalbear.mirrord

import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPanel

class MirrordSettingsComponent {

    private val versionCheckEnabled = JBCheckBox("Version check")
    private val notificationsEnabled = MirrordSettingsState
        .NotificationId
        .values()
        .associateWith { JBCheckBox(it.presentableName) }
    private val usageBannerEnabled = JBCheckBox("Show usage banner on startup")

    private val autoUpdate = FormBuilder
        .createFormBuilder()
        .addComponent(JBCheckBox("Auto update mirrord binary"))
        .addComponent(JBTextField("Version"))
        .addComponentFillVertically(JPanel(), 0)
        .panel

    val panel: JPanel = FormBuilder
        .createFormBuilder()
        .addComponent(usageBannerEnabled)
        .addComponent(versionCheckEnabled)
        .addComponent(autoUpdate)
        .addSeparator()
        .addComponent(JBLabel("Notify when:"))
        .apply {
            notificationsEnabled.forEach {
                addComponent(it.value)
            }
        }
        .addComponentFillVertically(JPanel(), 0)
        .panel

    val preferredFocusedComponent: JComponent
        get() = versionCheckEnabled

    var usageBannerEnabledStatus: Boolean
        get() = usageBannerEnabled.isSelected
        set(value) {
            usageBannerEnabled.isSelected = value
        }

    var versionCheckEnabledStatus: Boolean
        get() = versionCheckEnabled.isSelected
        set(newStatus) {
            versionCheckEnabled.isSelected = newStatus
        }

    var notificationsDisabledStatus: Set<MirrordSettingsState.NotificationId>
        get() = notificationsEnabled.filter { !it.value.isSelected }.keys
        set(value) = notificationsEnabled.forEach {
            it.value.isSelected = !value.contains(it.key)
        }

    var autoUpdateEnabledStatus: Boolean
        get() = autoUpdate.isEnabled
        set(value) {
            autoUpdate.isEnabled = value
        }
}

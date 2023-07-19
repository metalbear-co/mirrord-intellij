package com.metalbear.mirrord

import com.intellij.ide.BrowserUtil
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPanel

class MirrordSettingsComponent {

    private val versionCheckEnabled = JBCheckBox("Version check")
    private val notificationsEnabled = MirrordSettingsState
        .NotificationId
        .values()
        .associateWith { JBCheckBox(it.presentableName) }

    val panel: JPanel = FormBuilder
        .createFormBuilder()
        .addComponent(versionCheckEnabled)
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
}

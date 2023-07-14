package com.metalbear.mirrord

import com.intellij.openapi.options.Configurable
import javax.swing.JComponent

class MirrordSettingsConfigurable : Configurable {
    private var mySettingsComponent: MirrordSettingsComponent? = null

    override fun getDisplayName(): String {
        return "mirrord"
    }

    override fun getPreferredFocusedComponent(): JComponent {
        return mySettingsComponent!!.preferredFocusedComponent
    }

    override fun createComponent(): JComponent {
        mySettingsComponent = MirrordSettingsComponent()
        return mySettingsComponent!!.panel
    }

    override fun isModified(): Boolean {
        val settings = MirrordSettingsState.instance.mirrordState
        return (mySettingsComponent!!.telemetryEnabledStatus != settings.telemetryEnabled) ||
            (mySettingsComponent!!.versionCheckEnabledStatus != settings.versionCheckEnabled) ||
            (mySettingsComponent!!.notificationsDisabledStatus != settings.disabledNotifications)
    }

    override fun apply() {
        val settings = MirrordSettingsState.instance.mirrordState
        settings.telemetryEnabled = mySettingsComponent!!.telemetryEnabledStatus
        settings.versionCheckEnabled = mySettingsComponent!!.versionCheckEnabledStatus
        settings.disabledNotifications = mySettingsComponent!!.notificationsDisabledStatus
    }

    override fun reset() {
        val settings = MirrordSettingsState.instance.mirrordState
        mySettingsComponent!!.telemetryEnabledStatus = settings.telemetryEnabled ?: false
        mySettingsComponent!!.versionCheckEnabledStatus = settings.versionCheckEnabled
        mySettingsComponent!!.notificationsDisabledStatus = settings.disabledNotifications.orEmpty()
    }

    override fun disposeUIResources() {
        mySettingsComponent = null
    }
}

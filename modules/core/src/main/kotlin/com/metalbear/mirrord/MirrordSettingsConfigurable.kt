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
        return (mySettingsComponent!!.versionCheckEnabledStatus != settings.versionCheckEnabled) ||
            (mySettingsComponent!!.notificationsDisabledStatus != settings.disabledNotifications) ||
            (mySettingsComponent!!.usageBannerEnabledStatus != settings.showUsageBanner)
    }

    override fun apply() {
        val settings = MirrordSettingsState.instance.mirrordState
        settings.versionCheckEnabled = mySettingsComponent!!.versionCheckEnabledStatus
        settings.disabledNotifications = mySettingsComponent!!.notificationsDisabledStatus
        settings.showUsageBanner = mySettingsComponent!!.usageBannerEnabledStatus
    }

    override fun reset() {
        val settings = MirrordSettingsState.instance.mirrordState
        mySettingsComponent!!.versionCheckEnabledStatus = settings.versionCheckEnabled ?: true
        mySettingsComponent!!.notificationsDisabledStatus = settings.disabledNotifications.orEmpty()
        mySettingsComponent!!.usageBannerEnabledStatus = settings.showUsageBanner
    }

    override fun disposeUIResources() {
        mySettingsComponent = null
    }
}

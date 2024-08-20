package com.metalbear.mirrord

import com.intellij.openapi.options.Configurable
import javax.swing.JComponent

class MirrordSettingsConfigurable : Configurable {
    private val mySettingsComponent by lazy(::MirrordSettingsComponent)

    override fun getDisplayName() = "mirrord"

    override fun getPreferredFocusedComponent(): JComponent = mySettingsComponent.preferredFocusedComponent

    override fun createComponent(): JComponent = mySettingsComponent.panel

    override fun isModified(): Boolean {
        val settings = MirrordSettingsState.instance.mirrordState
        return mySettingsComponent.run {
            (versionCheckEnabledStatus != settings.versionCheckEnabled) ||
                (notificationsDisabledStatus != settings.disabledNotifications) ||
                (usageBannerEnabledStatus != settings.showUsageBanner) ||
                (autoUpdateEnabledStatus != settings.autoUpdate) ||
                (mirrordVersionStatus != settings.mirrordVersion) ||
                (enabledOnStartupStatus != settings.enabledByDefault)
        }
    }

    override fun apply() {
        val settings = MirrordSettingsState.instance.mirrordState
        mySettingsComponent.run {
            settings.versionCheckEnabled = versionCheckEnabledStatus
            settings.disabledNotifications = notificationsDisabledStatus
            settings.showUsageBanner = usageBannerEnabledStatus
            settings.autoUpdate = autoUpdateEnabledStatus
            settings.mirrordVersion = mirrordVersionStatus
            settings.enabledByDefault = enabledOnStartupStatus
        }
    }

    override fun reset() {
        val settings = MirrordSettingsState.instance.mirrordState
        mySettingsComponent.run {
            versionCheckEnabledStatus = settings.versionCheckEnabled ?: true
            autoUpdateEnabledStatus = settings.autoUpdate
            mirrordVersionStatus = settings.mirrordVersion
            notificationsDisabledStatus = settings.disabledNotifications.orEmpty()
            usageBannerEnabledStatus = settings.showUsageBanner
            enabledOnStartupStatus = settings.enabledByDefault
        }
    }

    override fun disposeUIResources() = Unit
}

package com.metalbear.mirrord

import com.github.zafarkhaja.semver.Version
import com.intellij.ide.plugins.PluginManagerConfigurable
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.util.SystemInfo
import java.net.URL
import java.time.LocalDateTime
import java.time.ZoneOffset

private val PLUGIN_ID = PluginId.getId("com.metalbear.mirrord")
val VERSION: String? = PluginManagerCore.getPlugin(PLUGIN_ID)?.version
private val OS: String = java.net.URLEncoder.encode(SystemInfo.OS_NAME, "utf-8")
private val VERSION_CHECK_ENDPOINT: String =
    "https://version.mirrord.dev/get-latest-version?source=3&version=$VERSION&platform=$OS"
private const val LAST_CHECK_KEY = "lastCheck"

class MirrordVersionCheck(private val service: MirrordProjectService) {
    /**
     * Fetch the latest version number, compare to local version. If there is a later version available, notify.
     * Return early without checking if already performed full check in the last 3 minutes.
     */
    fun checkVersion() {
        val pc = PropertiesComponent.getInstance() // Don't pass project, to get ide-wide persistence.
        val lastCheckEpoch = pc.getLong(LAST_CHECK_KEY, 0)
        val nowUTC = LocalDateTime.now(ZoneOffset.UTC)
        val lastCheckUTCDateTime = LocalDateTime.ofEpochSecond(lastCheckEpoch, 0, ZoneOffset.UTC)
        if (lastCheckUTCDateTime.isAfter(nowUTC.minusMinutes(3))) {
            return // Already checked in the last 3 hours. Don't check again yet.
        }
        val nowEpoch = nowUTC.toEpochSecond(ZoneOffset.UTC)
        pc.setValue(LAST_CHECK_KEY, nowEpoch.toString())
        val remoteVersion = Version.valueOf(URL(VERSION_CHECK_ENDPOINT).readText())

        // Don't show user anything
        if (!MirrordSettingsState.instance.mirrordState.versionCheckEnabled) {
            return
        }


        val localVersion = Version.valueOf(VERSION)
        if (localVersion.lessThan(remoteVersion)) {
            ApplicationManager.getApplication().invokeLater {
                service.notifier.notification(
                    "The version of the mirrord plugin is outdated. Would you like to update it now?",
                    NotificationType.INFORMATION
                )
                    .withAction("Update") { _, n ->
                        try {
                            PluginManagerConfigurable.showPluginConfigurable(service.project, listOf(PLUGIN_ID))
                        } finally {
                            n.expire()
                        }
                    }
                    .withAction("Don't show again") { _, n ->
                        MirrordSettingsState.instance.mirrordState.versionCheckEnabled = false
                        n.expire()
                    }
                    .fire()
            }
        }
        return
    }
}

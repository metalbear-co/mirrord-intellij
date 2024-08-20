package com.metalbear.mirrord

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

@State(name = "MirrordSettingsState", storages = [Storage("mirrord.xml")])
open class MirrordSettingsState : PersistentStateComponent<MirrordSettingsState.MirrordState> {
    companion object {
        val instance: MirrordSettingsState
            get() = ApplicationManager.getApplication().service<MirrordSettingsState>()
    }

    var mirrordState: MirrordState = MirrordState()

    override fun getState(): MirrordState {
        return mirrordState
    }

    // after automatically loading our save state,  we will keep reference to it
    override fun loadState(state: MirrordState) {
        mirrordState = state
    }

    enum class NotificationId(val presentableName: String) {
        RUNNING_TARGETLESS("mirrord running targetless"),
        ACTIVE_CONFIG_REMOVED("active mirrord config is removed"),
        ACTIVE_CONFIG_USED("active mirrord config is used"),
        DEFAULT_CONFIG_USED("default mirrord config is used"),
        POSSIBLY_OUTDATED_BINARY_USED("possibly outdated mirrord binary is used"),
        ACTIVE_CONFIG_MOVED("active mirrord config is moved"),
        AGENT_VERSION_MISMATCH("agent version does not match version of the local mirrord installation"),
        PLUGIN_REVIEW("mirrord occasionally asks for plugin review"),
        DISCORD_INVITE("mirrord offers a Discord server invitation"),
        MIRRORD_FOR_TEAMS("mirrord occasionally informs about mirrord for Teams")
    }

    class MirrordState {
        var versionCheckEnabled: Boolean? = null
        var autoUpdate: Boolean = true
        var mirrordVersion: String = ""
        var lastChosenTarget: String? = null
        var showPodsInSelection: Boolean? = null
        var showDeploymentsInSelection: Boolean? = null
        var showRolloutsInSelection: Boolean? = null
        var disabledNotifications: Set<NotificationId>? = null
        var showUsageBanner: Boolean = true
        var runsCounter: Int = 0
        var operatorUsed: Boolean = false
        var enabledByDefault: Boolean = false

        fun disableNotification(id: NotificationId) {
            disabledNotifications = disabledNotifications.orEmpty() + id
        }

        fun isNotificationDisabled(id: NotificationId): Boolean {
            return disabledNotifications?.contains(id) ?: false
        }
    }
}

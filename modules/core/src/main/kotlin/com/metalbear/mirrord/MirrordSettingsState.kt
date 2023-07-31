package com.metalbear.mirrord

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*

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
        DEFAULT_CONFIG_CREATED("default mirrord config is created"),
        POSSIBLY_OUTDATED_BINARY_USED("possibly outdated mirrord binary is used"),
        ACTIVE_CONFIG_MOVED("active mirrord config is moved")
    }

    class MirrordState {
        var versionCheckEnabled: Boolean? = null
        var lastChosenTarget: String? = null
        var showPodsInSelection: Boolean? = null
        var showDeploymentsInSelection: Boolean? = null
        var showRolloutsInSelection: Boolean? = null
        var disabledNotifications: Set<NotificationId>? = null

        fun disableNotification(id: NotificationId) {
            disabledNotifications = disabledNotifications.orEmpty() + id
        }

        // TODO(alex) [high] 2023-07-31: How do I change this config value?
        fun tickFeedbackCounter(): int {
        }

        fun isNotificationDisabled(id: NotificationId): Boolean {
            return disabledNotifications?.contains(id) ?: false
        }
    }
}

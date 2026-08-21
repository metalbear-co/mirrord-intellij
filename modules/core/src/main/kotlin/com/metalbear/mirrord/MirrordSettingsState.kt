package com.metalbear.mirrord

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/**
 * Default timeout (in minutes) for a mirrord task, i.e. the `mirrord ext` execution that
 * spins up the agent and returns the patched environment.
 */
const val DEFAULT_TASK_TIMEOUT_MINUTES: Int = 2

/** Layer log filter (deliberately not `RUST_LOG`, so a Rust app's own logging is unaffected). */
private const val MIRRORD_LOG_ENV = "MIRRORD_LOG"

/** Log filter for mirrord's other Rust processes (CLI/intproxy/agent). */
private const val RUST_LOG_ENV = "RUST_LOG"

/** Directory the layer writes a per-process trace log file into. */
private const val MIRRORD_LAYER_LOG_PATH_ENV = "MIRRORD_LAYER_LOG_PATH"

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
        SLACK_INVITE("mirrord offers a Slack server invitation"),
        MIRRORD_FOR_TEAMS("mirrord occasionally informs about mirrord for Teams"),
        NEWSLETTER_SIGNUP("mirrord occasionally informs about the mirrord newsletter"),
        MIRRORD_BINARY_PATH_INVALID("custom mirrord binary path is invalid or not executable"),
        LARGE_BINARY_STAGED("a large mirrord binary is copied into the target environment")
    }

    class MirrordState {
        var versionCheckEnabled: Boolean? = null
        var autoUpdate: Boolean = true
        var mirrordVersion: String = ""
        var mirrordBinaryPath: String = ""
        var lastChosenTarget: String? = null
        var showPodsInSelection: Boolean? = null
        var showDeploymentsInSelection: Boolean? = null
        var showRolloutsInSelection: Boolean? = null
        var disabledNotifications: Set<NotificationId>? = null
        var showUsageBanner: Boolean = true
        var runsCounter: Int = 0
        var operatorUsed: Boolean = false
        var enabledByDefault: Boolean = false
        var taskTimeoutMinutes: Int = DEFAULT_TASK_TIMEOUT_MINUTES

        /** When on, every mirrord run enables trace logging in mirrord's processes. */
        var troubleshootingLogsEnabled: Boolean = false

        /**
         * Opt out of running WSL through the IntelliJ Platform's execution environment layer
         * (EEL) and fall back to the legacy `wsl.exe` integration.
         *
         * WSL normally goes through EEL, the same mechanism that makes Dev Containers work,
         * so there is one code path for every non-local environment. This flag exists purely
         * as an escape hatch: if a WSL setup regresses under EEL, turning this on restores the
         * previous `getWslPath` + `patchCommandLine` behaviour without downgrading the plugin.
         *
         * Expected to be removed once EEL-backed WSL has been validated across enough setups.
         */
        var useLegacyWsl: Boolean = false

        /** Directory the layer writes its per-process trace log into ([MIRRORD_LAYER_LOG_PATH_ENV]). */
        var troubleshootingLogsPath: String = ""

        fun disableNotification(id: NotificationId) {
            disabledNotifications = disabledNotifications.orEmpty() + id
        }

        fun isNotificationDisabled(id: NotificationId): Boolean {
            return disabledNotifications?.contains(id) ?: false
        }

        /**
         * Environment variables that turn on verbose layer logging for troubleshooting.
         *
         * These are merged into the launched process's mirrord environment, including through the
         * Windows `MIRRORD_CHILD_ENV` payload. `RUST_LOG` is deliberately excluded because it
         * belongs on the mirrord CLI and intproxy, not on a user's Rust application.
         *
         * `MIRRORD_LAYER_LOG_PATH` is omitted when blank, so layer logging falls back to stderr.
         */
        fun troubleshootingLayerEnvVars(
            logPathTransform: (String) -> String = { it }
        ): Map<String, String> {
            if (!troubleshootingLogsEnabled) {
                return emptyMap()
            }
            val env = linkedMapOf(MIRRORD_LOG_ENV to "trace")
            troubleshootingLogsPath.trim().takeIf { it.isNotEmpty() }
                ?.let(logPathTransform)
                ?.takeIf { it.isNotEmpty() }
                ?.let {
                    env[MIRRORD_LAYER_LOG_PATH_ENV] = it
                }
            return env
        }

        /** Trace logging applied only to mirrord's CLI process and the intproxy it starts. */
        fun troubleshootingCliEnvVars(): Map<String, String> =
            if (troubleshootingLogsEnabled) {
                mapOf(RUST_LOG_ENV to "trace")
            } else {
                emptyMap()
            }
    }
}

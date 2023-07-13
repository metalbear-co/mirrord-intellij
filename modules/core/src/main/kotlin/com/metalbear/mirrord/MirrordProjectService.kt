package com.metalbear.mirrord

import com.intellij.ide.BrowserUtil
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager

@Service(Service.Level.PROJECT)
class MirrordProjectService(val project: Project) : Disposable {
    val configApi: MirrordConfigAPI = MirrordConfigAPI(this)

    val execManager: MirrordExecManager = MirrordExecManager(this)

    val versionCheck: MirrordVersionCheck = MirrordVersionCheck(this)

    val binaryManager: MirrordBinaryManager = MirrordBinaryManager(this)

    val mirrordApi: MirrordApi = MirrordApi(this)

    val notifier: MirrordNotifier = MirrordNotifier(this)

    @Volatile
    var activeConfig: VirtualFile? = null

    @Volatile
    private var _enabled = false

    var enabled: Boolean
        get() = _enabled
        set(value) {
            if (value == _enabled) {
                return;
            }

            if (value) {
                notifier.notifySimple("mirrord enabled", NotificationType.INFORMATION)
            } else {
                notifier.notifySimple("mirrord disabled", NotificationType.INFORMATION)
            }

            this._enabled = value

            if (MirrordSettingsState.instance.mirrordState.telemetryEnabled == null) {
                telemetryConsent()
            }
        }

    /**
     * Shows a notification asking for consent to send telemetries
     */
    private fun telemetryConsent() {
        ApplicationManager.getApplication().invokeLater {
            notifier
                .notification(
                    "Allow mirrord to send telemetries",
                    NotificationType.INFORMATION
                )
                .withAction("Deny (disables version check)") { _, n ->
                    MirrordSettingsState.instance.mirrordState.telemetryEnabled = false
                    MirrordSettingsState.instance.mirrordState.versionCheckEnabled = false
                    n.expire()
                }
                .withAction("More info") { _, _ ->
                    BrowserUtil.browse("https://github.com/metalbear-co/mirrord/blob/main/TELEMETRY.md")
                }
                .withAction("Allow") { _, n ->
                    MirrordSettingsState.instance.mirrordState.telemetryEnabled = true
                    MirrordSettingsState.instance.mirrordState.versionCheckEnabled = true
                    n.expire()
                }
                .withCollapseDirection(Notification.CollapseActionsDirection.KEEP_RIGHTMOST)
                .fire()
        }
    }

    override fun dispose() {}

    init {
        VirtualFileManager.getInstance().addAsyncFileListener(MirrordActiveConfigWatch(this), this)
    }
}

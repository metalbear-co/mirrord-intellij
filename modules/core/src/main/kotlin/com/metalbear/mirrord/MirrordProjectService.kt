package com.metalbear.mirrord

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager

@Service(Service.Level.PROJECT)
class MirrordProjectService(val project: Project) : Disposable {
    val configApi: MirrordConfigAPI = MirrordConfigAPI(this)

    val execManager: MirrordExecManager = MirrordExecManager(this)

    val versionCheck: MirrordVersionCheck = MirrordVersionCheck(this)

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
                return
            }

            if (value) {
                notifier.notifySimple("mirrord enabled", NotificationType.INFORMATION)
            } else {
                notifier.notifySimple("mirrord disabled", NotificationType.INFORMATION)
            }

            this._enabled = value

            if (MirrordSettingsState.instance.mirrordState.versionCheckEnabled == null) {
                versionCheckConsent()
            }
        }

    /**
     * Shows a notification asking for consent to send telemetries
     */
    private fun versionCheckConsent() {
        ApplicationManager.getApplication().invokeLater {
            notifier
                .notification(
                    "Allow mirrord plugin version check",
                    NotificationType.INFORMATION
                )
                .withAction("Deny") { _, n ->
                    MirrordSettingsState.instance.mirrordState.versionCheckEnabled = false
                    n.expire()
                }
                .withAction("Allow") { _, n ->
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

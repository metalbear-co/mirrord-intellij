package com.metalbear.mirrord

import com.intellij.notification.NotificationType
import com.intellij.openapi.vfs.AsyncFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent

class MirrordActiveConfigWatch(private val service: MirrordProjectService) : AsyncFileListener {
    override fun prepareChange(events: MutableList<out VFileEvent>): AsyncFileListener.ChangeApplier? {
        val removeActive = service.activeConfig?.url?.let { activeUrl ->
            events
                .filter { it is VFileDeleteEvent || it is VFileMoveEvent }
                .any { it.file?.url == activeUrl }
        } ?: false

        return if (removeActive) {
            object : AsyncFileListener.ChangeApplier {
                override fun afterVfsChange() {
                    service.activeConfig = null
                    service.notifier.notification(
                        "mirrord active config has been removed",
                        NotificationType.WARNING
                    )
                        .withDontShowAgain(MirrordSettingsState.NotificationId.ACTIVE_CONFIG_REMOVED)
                        .fire()
                }
            }
        } else {
            null
        }
    }
}

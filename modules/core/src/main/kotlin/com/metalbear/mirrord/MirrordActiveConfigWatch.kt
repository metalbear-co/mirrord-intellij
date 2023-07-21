package com.metalbear.mirrord

import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.vfs.AsyncFileListener
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent

class MirrordActiveConfigWatch(private val service: MirrordProjectService) : AsyncFileListener {
    /**
     * @param url null means that the file has been removed
     */
    class ConfigMovedTo(val url: String?)

    override fun prepareChange(events: MutableList<out VFileEvent>): AsyncFileListener.ChangeApplier? {
        val result: ConfigMovedTo? = service
            .activeConfig
            ?.url
            ?.let { activeUrl ->
                events
                    .filter { it.file?.let { file -> activeUrl.startsWith(file.url) } ?: false }
                    .firstNotNullOfOrNull {
                        when (it) {
                            is VFileDeleteEvent -> ConfigMovedTo(null)
                            is VFileMoveEvent -> ConfigMovedTo(activeUrl.replace(it.oldParent.url, it.newParent.url))
                            else -> null
                        }
                    }
            }

        return result?.let {
            object : AsyncFileListener.ChangeApplier {
                override fun afterVfsChange() {
                    val newActiveConfig = it.url?.let { url ->
                        ReadAction.compute<VirtualFile, Exception> {
                            VirtualFileManager.getInstance().findFileByUrl(url)
                        }
                    }

                    service.activeConfig = newActiveConfig

                    val notification = if (newActiveConfig == null) {
                        service.notifier.notification(
                            "mirrord active config has been removed",
                            NotificationType.WARNING
                        )
                            .withDontShowAgain(MirrordSettingsState.NotificationId.ACTIVE_CONFIG_REMOVED)
                    } else {
                        service.notifier.notification(
                            "mirrord active config has been moved to ${newActiveConfig.presentableUrl}",
                            NotificationType.WARNING
                        )
                            .withDontShowAgain(MirrordSettingsState.NotificationId.ACTIVE_CONFIG_MOVED)
                    }

                    notification.fire()

                    return
                }
            }
        }
    }
}

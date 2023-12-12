@file:Suppress("DialogTitleCapitalization")

package com.metalbear.mirrord

import com.intellij.ide.BrowserUtil
import com.intellij.notification.*
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import java.nio.file.Path

/**
 * Mirrord notification handler.
 */
class MirrordNotifier(private val service: MirrordProjectService) {
    private val notificationManager: NotificationGroup = NotificationGroupManager
        .getInstance()
        .getNotificationGroup("mirrord Notification Handler")

    private val warningNotificationManager: NotificationGroup = NotificationGroupManager
        .getInstance()
        .getNotificationGroup("mirrord Warning Notification Handler")

    class MirrordNotification(private val inner: Notification, private val project: Project) {
        private var id: MirrordSettingsState.NotificationId? = null

        /**
         * Adds a new action to this notification.
         * Exceptions thrown in the action are caught, converted to `MirrordError` and displayed to the user.
         *
         * @see MirrordError
         *
         * @param name name of the action, visible in the notification
         * @param handler function to call when this action is dispatched
         */
        fun withAction(name: String, handler: (e: AnActionEvent, notification: Notification) -> Unit): MirrordNotification {
            inner.addAction(object : NotificationAction(name) {
                override fun actionPerformed(e: AnActionEvent, notification: Notification) {
                    try {
                        handler(e, notification)
                    } catch (error: MirrordError) {
                        error.showHelp(project)
                    } catch (error: Throwable) {
                        MirrordError(error.message ?: "An error occurred", error).showHelp(project)
                    }
                }
            })

            return this
        }

        fun withOpenFile(file: VirtualFile): MirrordNotification {
            return withAction("Open") { _, _ ->
                FileEditorManager.getInstance(project).openFile(file, true)
            }
        }

        fun withOpenPath(rawPath: String): MirrordNotification {
            return withAction("Open") { _, _ ->
                val path = Path.of(rawPath)
                val file = VirtualFileManager.getInstance().findFileByNioPath(path) ?: throw Exception("file $rawPath not found")
                FileEditorManager.getInstance(project).openFile(file, true)
            }
        }

        fun withDontShowAgain(id: MirrordSettingsState.NotificationId): MirrordNotification {
            this.id = id
            return withAction("Don't show again") { _, n ->
                MirrordSettingsState.instance.mirrordState.disableNotification(id)
                n.expire()
            }
        }

        fun withLink(name: String, url: String): MirrordNotification {
            return withAction(name) { _, _ -> BrowserUtil.browse(url) }
        }

        fun withCollapseDirection(direction: Notification.CollapseActionsDirection): MirrordNotification {
            inner.setCollapseDirection(direction)

            return this
        }

        fun fire() {
            id?.let {
                if (MirrordSettingsState.instance.mirrordState.isNotificationDisabled(it)) {
                    return
                }
            }
            inner.notify(project)
        }
    }

    fun notification(message: String, type: NotificationType): MirrordNotification {
        return MirrordNotification(
            notificationManager.createNotification("mirrord", message, type),
            service.project
        )
    }

    fun notifySimple(message: String, type: NotificationType) {
        ApplicationManager.getApplication().invokeLater {
            val notificationManager = when (type) {
                NotificationType.WARNING -> warningNotificationManager
                else -> notificationManager
            }

            notificationManager
                .createNotification("mirrord", message, type)
                .notify(service.project)
        }
    }

    fun notifyRichError(message: String) {
        ApplicationManager.getApplication().invokeLater {
            notification(message, NotificationType.ERROR)
                .withAction("Get support on Discord") { _, n ->
                    BrowserUtil.browse("https://discord.gg/metalbear")
                    n.expire()
                }
                .withAction("Report on GitHub") { _, n ->
                    BrowserUtil.browse("https://github.com/metalbear-co/mirrord/issues/new?assignees=&labels=bug&template=bug_report.yml")
                    n.expire()
                }
                .withAction("Send us an email") { _, n ->
                    BrowserUtil.browse("mailto:hi@metalbear.co")
                    n.expire()
                }
                .fire()
        }
    }
}

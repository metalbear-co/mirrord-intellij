package com.metalbear.mirrord

import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationType

private const val NOTIFY_AFTER: Int = 100
private const val NOTIFY_EVERY: Int = 30
private const val COUNTER_KEY: String = "mirrordForTeamsCounter"

private const val DEPLOYMENT_EXEC_MESSAGE: String = "When targeting multi-pod deployments, mirrord impersonates the first pod in the deployment. Support for multi-pod impersonation requires the mirrord operator, which is part of mirrord for Teams."
private const val REGULAR_EXEC_MESSAGE: String = "For more features of mirrord, including multi-pod impersonation, check out mirrord for Teams."
class MirrordRunCounter(private val service: MirrordProjectService) {
    private fun showNotification(message: String) {
        service.notifier.notification(message, NotificationType.INFORMATION).withLink("Try it now", MIRRORD_FOR_TEAMS_URL).withDontShowAgain(MirrordSettingsState.NotificationId.MIRRORD_FOR_TEAMS).fire()
    }
    fun bump(isDeploymentExec: Boolean) {
        val pc = PropertiesComponent.getInstance()

        val previousRuns = pc.getInt(COUNTER_KEY, 0)
        pc.setValue(COUNTER_KEY, (previousRuns + 1).toString())

        if (isDeploymentExec) {
            this.showNotification(DEPLOYMENT_EXEC_MESSAGE)
        } else if (previousRuns >= NOTIFY_AFTER) {
            if (previousRuns == NOTIFY_AFTER || (previousRuns - NOTIFY_AFTER) % NOTIFY_EVERY == 0) {
                this.showNotification(REGULAR_EXEC_MESSAGE)
            }
        }
    }
}

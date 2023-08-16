package com.metalbear.mirrord

import com.google.gson.Gson
import com.intellij.execution.ExecutionException
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

open class MirrordError(private val richMessage: String, private val help: String?, override val cause: Throwable?) : ExecutionException(cause) {
    override val message: String = "mirrord failed"

    companion object {
        fun fromStdErr(processStdErr: String): MirrordError {
            val info = try {
                val trimmedError = processStdErr.removePrefix("Error: ")
                val gson = Gson()
                val error = gson.fromJson(trimmedError, Error::class.java)
                Pair(error.message, error.help)
            } catch (e: Throwable) {
                MirrordLogger.logger.debug("failed to deserialize stderr: $processStdErr", e)
                Pair(processStdErr, null)
            }

            return MirrordError(info.first, info.second, null)
        }
    }

    constructor(richMessage: String) : this(richMessage, null, null)

    constructor(richMessage: String, help: String) : this(richMessage, help, null)

    constructor(richMessage: String, cause: Throwable) : this(richMessage, null, cause)

    fun showHelp(project: Project) {
        val notifier = project
            .service<MirrordProjectService>()
            .notifier

        notifier.notifyRichError(richMessage)
        help?.let {
            notifier.notifySimple(it, NotificationType.INFORMATION)
        }
    }
}

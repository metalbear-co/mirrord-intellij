package com.metalbear.mirrord

import com.google.gson.Gson
import com.intellij.execution.ExecutionException
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

const val MIRRORD_INITIALIZATION_TIMEOUT_ERROR =
    "mirrord initialization timed out. Check that your Kubernetes cluster and target are reachable, " +
        "or increase the mirrord task timeout in Settings > Tools > mirrord."

const val MIRRORD_INITIALIZATION_UNKNOWN_ERROR =
    "mirrord initialization failed, but mirrord did not provide a detailed error. Check the mirrord logs for details."

open class MirrordError(
    private val richMessage: String,
    private val help: String?,
    override val cause: Throwable?,
    private val alreadyReported: Boolean = false
) : ExecutionException(cause) {
    override val message: String = "mirrord failed"

    companion object {
        fun fromStdErr(
            processStdErr: String,
            fallbackMessage: String? = null,
            alreadyReported: Boolean = false
        ): MirrordError {
            if (alreadyReported) {
                return MirrordError("", null, null, true)
            }

            val info = try {
                val trimmedError = processStdErr.removePrefix("Error: ")
                val gson = Gson()
                val error = gson.fromJson(trimmedError, Error::class.java)
                Pair(error.message, error.help)
            } catch (e: Throwable) {
                MirrordLogger.logger.debug("failed to deserialize stderr: $processStdErr", e)
                Pair(processStdErr, null)
            }

            return MirrordError(
                info.first.takeIf { it.isNotBlank() } ?: fallbackMessage.orEmpty(),
                info.second,
                null
            )
        }

        fun initializationTimedOut() = MirrordError(MIRRORD_INITIALIZATION_TIMEOUT_ERROR)
    }

    constructor(richMessage: String) : this(richMessage, null, null, false)

    constructor(richMessage: String, help: String) : this(richMessage, help, null, false)

    constructor(richMessage: String, cause: Throwable) : this(richMessage, null, cause, false)

    internal fun richMessageForTest() = richMessage

    internal fun helpForTest() = help

    fun showHelp(project: Project) {
        if (alreadyReported) {
            return
        }

        val notifier = project
            .service<MirrordProjectService>()
            .notifier

        notifier.notifyRichError(richMessage)
        help?.let {
            notifier.notifySimple(it, NotificationType.INFORMATION)
        }
    }
}

package com.metalbear.mirrord

import com.google.gson.Gson
import com.intellij.execution.ExecutionException
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.metalbear.mirrord.bifrost.MirrordEnvironment

open class MirrordError(private val richMessage: String, private val help: String?, override val cause: Throwable?) : ExecutionException(cause) {
    override val message: String = "mirrord failed"

    companion object {
        /**
         * @param environment Where the failed command ran, and `envVars` the variables it ran with
         * (such as `HOME`): an expired login is renewed with both, so the next run reads the new
         * login.
         */
        fun fromStdErr(processStdErr: String, environment: MirrordEnvironment? = null, envVars: Map<String, String>? = null): MirrordError {
            val error = try {
                val trimmedError = processStdErr.removePrefix("Error: ")
                Gson().fromJson(trimmedError, Error::class.java)
            } catch (e: Throwable) {
                MirrordLogger.logger.debug("failed to deserialize stderr: $processStdErr", e)
                null
            } ?: return MirrordError(processStdErr, null, null)

            if (error.code == LOGIN_EXPIRED_CODE) {
                return MirrordLoginExpiredError(error.message, null, environment, envVars)
            }
            return MirrordError(error.message, error.help, null)
        }
    }

    constructor(richMessage: String) : this(richMessage, null, null)

    constructor(richMessage: String, help: String) : this(richMessage, help, null)

    constructor(richMessage: String, cause: Throwable) : this(richMessage, null, cause)

    open fun showHelp(project: Project) {
        val notifier = project
            .service<MirrordProjectService>()
            .notifier

        notifier.notifyRichError(richMessage)
        help?.let {
            notifier.notifySimple(it, NotificationType.INFORMATION)
        }
    }
}

/**
 * The operator login in use expired. Shown as the CLI's message with a Log in action, instead of the
 * generic failure.
 *
 * @param url The login to renew; `null` uses the one `mirrord whoami` names as active.
 * @param environment Where the session ran (a WSL distribution, a container), so the Log in action
 * writes the login the next run reads; `null` uses the project's.
 * @param envVars The run's environment variables, for the same reason: `HOME` decides which login
 * store the CLI reads.
 */
class MirrordLoginExpiredError(
    val expiredMessage: String,
    val url: String?,
    private val environment: MirrordEnvironment?,
    private val envVars: Map<String, String>? = null
) : MirrordError(expiredMessage) {
    override fun showHelp(project: Project) {
        project.service<MirrordLoginService>().showLoginExpired(expiredMessage, url, LoginTarget(environment, envVars))
    }
}

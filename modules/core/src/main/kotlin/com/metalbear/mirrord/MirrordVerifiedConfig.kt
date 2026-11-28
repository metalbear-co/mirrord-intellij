package com.metalbear.mirrord

import com.google.gson.Gson
import com.intellij.notification.NotificationType
import com.intellij.util.asSafely

/**
 * Type for dealing with the output from `mirrord verify-config`.
 *
 * It parses the output from the command and displays warnings/errors.
 *
 * The command outputs either a `type = "Success"` or `type = "Fail"`.
 */
class MirrordVerifiedConfig(private val verified: String, private val notifier: MirrordNotifier) {
    /**
     * The errors when the output is `type = "Fail"`.
     */
    private val errors: List<String>?

    /**
     * `verify-config` also outputs the `MirrordConfig` that was set, when `type = "Success"`.
     */
    val config: String?

    /**
     * Set when the failure is the expired operator login. Its message is not shown as a plain error:
     * the caller shows it with a Log in action.
     */
    val loginExpired: ExpiredLogin?

    init {
        val gson = Gson()

        this.config = gson.fromJson(this.verified, Map::class.java).let { verified ->
            verified["warnings"].asSafely<List<String>>().also { warnings ->
                warnings?.forEach { this.notifier.notifySimple(it, NotificationType.WARNING) }
            }

            val errors = verified["errors"].asSafely<List<String>>()
            this.errors = errors
            this.loginExpired = loginExpiredOf(verified, errors)
            if (this.loginExpired == null) {
                errors?.forEach { this.notifier.notifySimple(it, NotificationType.ERROR) }
            }

            verified["config"].toString()
        }
        MirrordLogger.logger.debug("verified config ${this.config}")
    }

    /**
     * The expired login a `Fail` names, with the error the CLI wrote for it.
     */
    data class ExpiredLogin(val message: String, val url: String)

    companion object {
        /**
         * The expired login a `Fail` names in `loginExpired`. Older CLIs leave the field out.
         */
        fun loginExpiredOf(verified: Map<*, *>, errors: List<String>?): ExpiredLogin? {
            val loginExpired = verified["loginExpired"].asSafely<Map<*, *>>() ?: return null
            val url = loginExpired["url"].asSafely<String>() ?: return null
            val host = loginExpired["host"].asSafely<String>() ?: url
            return ExpiredLogin(errors?.firstOrNull() ?: "Your login to $host expired.", url)
        }
    }

    fun isError(): Boolean {
        return !this.errors.isNullOrEmpty()
    }
}

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
     * The warnings when the output is `type = "Success"`, but some options might be conflicting.
     */
    private val warnings: List<String>?

    /**
     * The errors when the output is `type = "Fail"`.
     */
    private val errors: List<String>?

    /**
     * `verify-config` also outputs the `MirrordConfig` that was set, when `type = "Success"`.
     */
    val config: String?

    init {
        val gson = Gson()

        this.config = gson.fromJson(this.verified, Map::class.java).let { verified ->
            val type = verified["type"].asSafely<String>()

            val warnings = verified["warnings"].asSafely<List<String>>().also { warnings ->
                warnings?.forEach { this.notifier.notifySimple(it, NotificationType.WARNING) }
            }
            this.warnings = warnings

            val errors = verified["errors"].asSafely<List<String>>().also { errors ->
                errors?.forEach { this.notifier.notifySimple(it, NotificationType.ERROR) }
            }
            this.errors = errors

            verified["config"].toString()
        }
        MirrordLogger.logger.debug("verified config ${this.config}")
    }

    fun isError(): Boolean {
        return !this.errors.isNullOrEmpty()
    }
}

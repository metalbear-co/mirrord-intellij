package com.metalbear.mirrord

import com.google.gson.Gson
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ApplicationNamesInfo
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.metalbear.mirrord.bifrost.MirrordEnvironment
import com.metalbear.mirrord.bifrost.MirrordEnvironments
import com.metalbear.mirrord.bifrost.TargetPath
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The `code` the CLI puts on the error of an expired operator login.
 */
const val LOGIN_EXPIRED_CODE = "mirrord::login_expired"

/**
 * Name of the task `mirrord login` starts, and finishes with its final message.
 */
const val LOGIN_TASK = "mirrord login"

/**
 * Shown when the CLI is older than `mirrord whoami -o json`.
 */
const val TOO_OLD_CLI = "The installed mirrord CLI is too old to list logins. Update mirrord to the latest release, " +
    "or turn on auto update in the mirrord settings."

/**
 * How long the menu reuses the logins it read, so opening it repeatedly does not run the CLI each time.
 */
private const val REFRESH_INTERVAL_MILLIS = 30_000L

/**
 * One operator login in `mirrord whoami -o json`.
 *
 * @param url What `mirrord login --url` and `mirrord logout --url` take.
 * @param expiresIn `in 6 hours`, or `expired`, when the CLI ran.
 */
data class OperatorLogin(val url: String, val host: String, val account: String, val expiresAt: String, val expiresIn: String, val active: Boolean)

/**
 * The mirrord Cloud login in `mirrord whoami -o json`.
 */
data class CloudLogin(val email: String, val expiresAt: String, val expiresIn: String)

/**
 * `mirrord whoami -o json`.
 *
 * @param operators Active login first, then the others from the most recent login to the oldest.
 * @param activeOperator URL of the login mirrord uses, kept when that login expired; `null` with the kubeconfig.
 */
data class Whoami(val operators: List<OperatorLogin>?, val activeOperator: String?, val kubeContext: String?, val cloud: CloudLogin?)

/**
 * What `mirrord whoami -o json` gave: the logins, or what to tell the user.
 */
data class WhoamiResult(val whoami: Whoami?, val failure: String?)

/**
 * Parses `mirrord whoami -o json`, `null` when the output is not that JSON.
 */
fun parseWhoami(stdout: String): Whoami? {
    val whoami = try {
        Gson().fromJson(stdout, Whoami::class.java)
    } catch (e: Throwable) {
        MirrordLogger.logger.debug("mirrord whoami -o json printed no JSON: $stdout", e)
        null
    }
    return whoami?.takeIf { it.operators != null }
}

/**
 * What to tell the user when `mirrord whoami -o json` fails: a CLI older than `-o json` rejects the
 * flag, anything else is the CLI's own error.
 */
fun whoamiFailure(stderr: String): String {
    if (stderr.contains("unexpected argument '-o'")) {
        return TOO_OLD_CLI
    }
    return "mirrord whoami failed: ${cliErrorText(stderr)}"
}

private val UNTIL_FORMAT = DateTimeFormatter.ofPattern("EEE HH:mm")

/**
 * How the summary names the end of a login: the day and the time on this machine, so the line stays
 * true however long it is shown.
 */
fun defaultFormatTime(time: Instant): String = UNTIL_FORMAT.format(time.atZone(ZoneId.systemDefault()))

/**
 * One line about the operator login in use at `now`, `null` when there are no operator logins at all,
 * so users who never log in see nothing new.
 */
fun loginSummary(whoami: Whoami, now: Instant, formatTime: (Instant) -> String = ::defaultFormatTime): String? {
    val operators = whoami.operators.orEmpty()
    if (operators.isEmpty()) {
        return null
    }
    val active = operators.find { it.active }
        ?: return "No operator login in use, " + (whoami.kubeContext?.let { "mirrord uses your kubeconfig context $it" } ?: "no kubeconfig was found")
    val expiresAt = runCatching { Instant.parse(active.expiresAt) }.getOrNull()
        ?: return "Logged in to ${active.host} as ${active.account}"
    if (!expiresAt.isAfter(now)) {
        return "Your login to ${active.host} expired"
    }
    return "Logged in to ${active.host} as ${active.account}, until ${formatTime(expiresAt)}"
}

/**
 * The text of a failed `mirrord login`, `logout` or `whoami`, which print their error for a person on
 * stderr (`Error:   × <message>` and a `help:` line), as one line for a notification.
 */
fun cliErrorText(stderr: String): String {
    // Terminal colors go first, in case the CLI decides to print them.
    return stderr
        .replace(Regex("\u001b\\[[0-9;]*m"), "")
        .lines()
        .map { it.replace(Regex("^\\s*Error:\\s*"), "").replace(Regex("^\\s*×\\s+"), "").trim() }
        .filter { it.isNotEmpty() }
        .joinToString(" ")
}

/**
 * The page to open in the browser, taken from the name of the task `mirrord login` starts while it
 * waits for the browser: `Approve the login in your browser. If it does not open, visit:\n\n<url>\n`.
 */
fun approvalUrl(taskName: String): String? {
    return Regex("visit:\\s+(\\S+)").find(taskName)?.groupValues?.get(1)
}

/**
 * How a login reads in the pickers.
 */
private fun OperatorLogin.label(): String {
    val marker = if (active) "(in use) " else ""
    val expiry = if (expiresIn == "expired") "expired" else "expires $expiresIn"
    return "$marker$host, $account, $expiry"
}

/**
 * Logging in to an operator, logging out, and showing the logins, through `mirrord login`, `logout` and
 * `whoami`.
 */
@Service(Service.Level.PROJECT)
class MirrordLoginService(private val project: Project) {
    /**
     * The logins last read, `null` until they were read once.
     */
    @Volatile
    private var lastWhoami: Whoami? = null

    @Volatile
    private var lastRefreshMillis = 0L

    private val service: MirrordProjectService
        get() = project.service()

    /**
     * One line about the operator login in use, computed now, so it says "expired" once the login
     * ended; `null` when there are no operator logins or they were not read yet.
     */
    fun summary(): String? = lastWhoami?.let { loginSummary(it, Instant.now()) }

    /**
     * Runs `block` with the CLI on a pooled thread, since finding the CLI can download it.
     *
     * @param quiet For calls the user did not start: finds the CLI without updating it, which sends no
     * requests and shows nothing, skips when there is none yet, and only logs failures.
     * @param environment Where to run; the project's when `null`.
     */
    private fun withCli(quiet: Boolean, environment: MirrordEnvironment? = null, block: (MirrordApi, TargetPath, MirrordEnvironment) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val target = environment ?: MirrordEnvironments.forProject(project)
                val binaries = service<MirrordBinaryManager>()
                val cli = if (quiet) {
                    binaries.findBinary(target).command ?: return@executeOnPooledThread
                } else {
                    binaries.getCliPath(ApplicationNamesInfo.getInstance().productName.lowercase(), target, project)
                }
                block(service.mirrordApi(null), cli, target)
            } catch (e: ProcessCanceledException) {
                MirrordLogger.logger.debug("mirrord login command cancelled", e)
            } catch (e: MirrordError) {
                if (quiet) MirrordLogger.logger.info("mirrord login command failed", e) else e.showHelp(project)
            } catch (e: Throwable) {
                if (quiet) MirrordLogger.logger.info("mirrord login command failed", e) else MirrordError(e.toString(), e).showHelp(project)
            }
        }
    }

    /**
     * Keeps the logins just read, when there are any.
     */
    private fun keep(result: WhoamiResult) {
        result.whoami?.let {
            lastWhoami = it
            lastRefreshMillis = System.currentTimeMillis()
        }
    }

    /**
     * Reads the logins again for the menu, without notifications. Skipped when they were read in the
     * last [REFRESH_INTERVAL_MILLIS], unless `force`.
     */
    fun refresh(force: Boolean = false) {
        if (!force && System.currentTimeMillis() - lastRefreshMillis < REFRESH_INTERVAL_MILLIS) {
            return
        }
        lastRefreshMillis = System.currentTimeMillis()
        withCli(quiet = true) { api, cli, environment -> keep(api.whoami(cli, environment)) }
    }

    /**
     * Shows the CLI's expired-login message with a Log in action for `url`, or for the login
     * `mirrord whoami` names as active when `url` is `null`.
     *
     * @param environment Where the session ran, so the new login lands where the next run reads it.
     */
    fun showLoginExpired(message: String, url: String?, environment: MirrordEnvironment?) {
        val fire = { loginUrl: String? ->
            val notification = service.notifier.notification(message, NotificationType.ERROR)
            loginUrl?.let { target ->
                notification.withAction("Log In") { _, n ->
                    n.expire()
                    login(target, environment)
                }
            }
            notification.fire()
        }

        if (url != null) {
            fire(url)
            refresh(force = true)
            return
        }
        withCli(quiet = true, environment) { api, cli, target ->
            val result = api.whoami(cli, target)
            keep(result)
            fire(result.whoami?.activeOperator)
        }
    }

    /**
     * Asks for the operator URL, offering the last one used. Runs on the event dispatch thread.
     */
    private fun askForUrl(): String? {
        val state = MirrordSettingsState.instance.mirrordState
        return Messages.showInputDialog(
            project,
            "The operator URL your admin gave you. mirrord opens your browser to sign in.",
            "Log In to a mirrord Operator",
            null,
            state.lastLoginUrl.ifBlank { "https://" },
            // The CLI checks the rest (HTTPS, that it is an operator) before any browser opens.
            object : InputValidator {
                override fun checkInput(inputString: String): Boolean {
                    val scheme = runCatching { URI(inputString.trim()).scheme }.getOrNull()
                    return scheme == "https" || scheme == "http"
                }

                override fun canClose(inputString: String) = checkInput(inputString)
            }
        )?.trim()?.ifBlank { null }
    }

    /**
     * Runs `mirrord login --url`. Without `url`, asks for it first. The CLI opens the browser itself;
     * the notification also offers the page, for when the browser did not open.
     *
     * @param environment Where to log in; the project's when `null`.
     */
    fun login(url: String? = null, environment: MirrordEnvironment? = null) {
        if (url == null) {
            ApplicationManager.getApplication().invokeLater {
                askForUrl()?.let { login(it, environment) }
            }
            return
        }

        MirrordSettingsState.instance.mirrordState.lastLoginUrl = url
        withCli(quiet = false, environment) { api, cli, target ->
            val finalMessage = api.login(cli, url, target) { page ->
                service.notifier.notification("Approve the mirrord login in your browser.", NotificationType.INFORMATION)
                    .withAction("Open Login Page") { _, n ->
                        n.expire()
                        BrowserUtil.browse(page)
                    }
                    .fire()
            }
            service.notifier.notifySimple(finalMessage ?: "Logged in to $url.", NotificationType.INFORMATION)
            keep(api.whoami(cli, target))
        }
    }

    /**
     * Reads the logins for a command the user started, telling them when the CLI cannot list them.
     */
    private fun whoamiForUser(api: MirrordApi, cli: TargetPath, environment: MirrordEnvironment): Whoami? {
        val result = api.whoami(cli, environment)
        result.failure?.let { service.notifier.notifySimple(it, NotificationType.ERROR) }
        keep(result)
        return result.whoami
    }

    /**
     * Shows `items` in a popup and runs `onChosen` with the picked one.
     */
    private fun <T> choose(title: String, items: List<T>, label: (T) -> String, onChosen: (T) -> Unit) {
        val labels = items.map(label)
        ApplicationManager.getApplication().invokeLater {
            JBPopupFactory.getInstance()
                .createPopupChooserBuilder(labels)
                .setTitle(title)
                .setItemChosenCallback { chosen -> onChosen(items[labels.indexOf(chosen)]) }
                .createPopup()
                .showCenteredInCurrentWindow(project)
        }
    }

    /**
     * Picks an operator login and runs `mirrord logout --url` for it.
     */
    fun logout() {
        withCli(quiet = false) { api, cli, environment ->
            val operators = whoamiForUser(api, cli, environment)?.operators ?: return@withCli
            if (operators.isEmpty()) {
                service.notifier.notifySimple("You are not logged in to any mirrord operator.", NotificationType.INFORMATION)
                return@withCli
            }
            choose("Log Out of a mirrord Operator", operators, { it.label() }) { picked ->
                withCli(quiet = false, environment) { logoutApi, logoutCli, logoutEnvironment ->
                    service.notifier.notifySimple(logoutApi.logout(logoutCli, picked.url, logoutEnvironment), NotificationType.INFORMATION)
                    keep(logoutApi.whoami(logoutCli, logoutEnvironment))
                }
            }
        }
    }

    /**
     * Shows what `mirrord whoami` shows. Picking a login logs in to it, which switches to it while it is
     * valid and opens the browser when it expired; the last item logs in to another operator.
     */
    fun showLogins() {
        withCli(quiet = false) { api, cli, environment ->
            val whoami = whoamiForUser(api, cli, environment) ?: return@withCli
            val fallback = whoami.kubeContext?.let { "Without an operator login, mirrord uses your kubeconfig context $it." }
                ?: "No kubeconfig was found."
            val cloud = whoami.cloud?.let {
                "mirrord Cloud: ${it.email}, " + if (it.expiresIn == "expired") "expired" else "expires ${it.expiresIn}"
            } ?: "mirrord Cloud: not logged in"
            val anotherOperator = "Log in to another operator..."

            val items = whoami.operators.orEmpty().map { it.label() to it.url } + (anotherOperator to null)
            service.notifier.notifySimple("${loginSummary(whoami, Instant.now()) ?: "No operator logins"}. $fallback $cloud", NotificationType.INFORMATION)
            choose("mirrord Operator Logins", items, { it.first }) { (_, url) -> login(url, environment) }
        }
    }
}

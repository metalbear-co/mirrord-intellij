package com.metalbear.mirrord

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class MirrordLoginTest {
    private val expired = "Your login to mirrord.corp.example.com expired. Run mirrord login --url https://mirrord.corp.example.com."

    private val login = OperatorLogin(
        url = "https://mirrord.corp.example.com",
        host = "mirrord.corp.example.com",
        account = "dev@corp.example.com",
        expiresAt = "2027-01-15T14:00:00Z",
        expiresIn = "in 6 hours",
        active = true
    )

    private fun whoami(operators: List<OperatorLogin> = listOf(login), activeOperator: String? = login.url) =
        Whoami(operators, activeOperator, "staging-eu", null)

    /** The launch path shows this error with a Log in action instead of the generic failure. */
    @Test
    fun expiredLoginErrorBecomesTheLoginExpiredError() {
        val stderr = """Error: {"message": "$expired","code": "$LOGIN_EXPIRED_CODE","severity": "error","causes": [],"help": "see mirrord whoami","labels": [],"related": []}"""

        val error = MirrordError.fromStdErr(stderr) as MirrordLoginExpiredError
        assertEquals(expired, error.expiredMessage)
        // The URL comes from `mirrord whoami` when the notification is shown.
        assertNull(error.url)
    }

    /** Errors of older CLIs carry no code and keep the generic failure. */
    @Test
    fun errorWithoutCodeStaysGeneric() {
        val stderr = """Error: {"message": "$expired","severity": "error","causes": [],"help": "see mirrord whoami","labels": [],"related": []}"""

        assertTrue(MirrordError.fromStdErr(stderr) !is MirrordLoginExpiredError)
    }

    /** With a config file, `verify-config` reports the expiry first and names the login to renew. */
    @Test
    fun verifiedConfigNamesTheExpiredLogin() {
        val verified = mapOf(
            "type" to "Fail",
            "errors" to listOf(expired),
            "loginExpired" to mapOf("host" to "mirrord.corp.example.com", "url" to "https://mirrord.corp.example.com")
        )

        assertEquals(
            MirrordVerifiedConfig.ExpiredLogin(expired, "https://mirrord.corp.example.com"),
            MirrordVerifiedConfig.loginExpiredOf(verified, listOf(expired))
        )
        assertNull(MirrordVerifiedConfig.loginExpiredOf(mapOf("type" to "Fail", "errors" to listOf("bad field")), listOf("bad field")))
    }

    @Test
    fun parsesWhoamiJson() {
        val json = """{"operators":[{"url":"https://mirrord.corp.example.com","host":"mirrord.corp.example.com","account":"dev@corp.example.com","expiresAt":"2027-01-15T14:00:00Z","expiresIn":"in 6 hours","active":true}],"activeOperator":"https://mirrord.corp.example.com","kubeContext":"staging-eu","cloud":null}"""

        assertEquals(whoami(), parseWhoami(json))
    }

    /** A CLI older than `-o json` prints its usage error instead. */
    @Test
    fun oldCliPrintsNoWhoamiJson() {
        assertNull(parseWhoami("error: unexpected argument '-o' found\n\nUsage: mirrord whoami\n"))
    }

    @Test
    fun summarizesTheLoginInUse() {
        val now = Instant.parse("2027-01-15T08:00:00Z")
        val iso = { time: Instant -> time.toString() }

        assertEquals(
            "Logged in to mirrord.corp.example.com as dev@corp.example.com, until 2027-01-15T14:00:00Z",
            loginSummary(whoami(), now, iso)
        )
        assertEquals(
            "No operator login in use, mirrord uses your kubeconfig context staging-eu",
            loginSummary(whoami(listOf(login.copy(active = false)), null), now, iso)
        )
    }

    /** The summary is computed when the menu opens, so a login that ended while the IDE was open reads as expired. */
    @Test
    fun summarySaysExpiredOnceTheEndHasPassed() {
        assertEquals(
            "Your login to mirrord.corp.example.com expired",
            loginSummary(whoami(), Instant.parse("2027-01-15T14:00:01Z"))
        )
    }

    @Test
    fun tellsAnOldCliFromOtherWhoamiFailures() {
        assertEquals(TOO_OLD_CLI, whoamiFailure("error: unexpected argument '-o' found\n\nUsage: mirrord whoami\n"))
        assertEquals(
            "mirrord whoami failed: failed to read ~/.mirrord/auth.json: permission denied",
            whoamiFailure("Error:   × failed to read ~/.mirrord/auth.json: permission denied\n")
        )
    }

    /** Users who never logged in to an operator see nothing new in the menu. */
    @Test
    fun noSummaryWithoutOperatorLogins() {
        assertNull(loginSummary(whoami(emptyList(), null), Instant.now()))
    }

    @Test
    fun turnsTheCliErrorIntoOneLine() {
        val stderr = "Error:   × You are not logged in to mirrord.corp.example.com.\n  help: Run mirrord whoami to see your logins.\n"

        assertEquals(
            "You are not logged in to mirrord.corp.example.com. help: Run mirrord whoami to see your logins.",
            cliErrorText(stderr)
        )
        assertEquals("broke", cliErrorText("\u001b[31mError:\u001b[0m   × broke"))
    }

    @Test
    fun takesTheLoginPageFromTheWaitingTask() {
        val name = "Approve the login in your browser. If it does not open, visit:\n\nhttps://mirrord.corp.example.com/apis/operator.metalbear.co/sso/authorize?state=xyz\n"

        assertEquals("https://mirrord.corp.example.com/apis/operator.metalbear.co/sso/authorize?state=xyz", approvalUrl(name))
        assertNull(approvalUrl("Exchanging grant for the login token"))
    }
}

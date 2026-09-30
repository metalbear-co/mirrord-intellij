package com.metalbear.mirrord

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MirrordErrorTest {

    @Test
    fun `timeout uses user-facing initialization message`() {
        val error = MirrordError.timedOut(duringInitialization = true)

        assertEquals(MIRRORD_INITIALIZATION_TIMEOUT_ERROR, error.richMessageForTest())
    }

    @Test
    fun `non-initialization timeout keeps generic task message`() {
        val error = MirrordError.timedOut(duringInitialization = false)

        assertEquals("mirrord process timed out", error.richMessageForTest())
    }

    @Test
    fun `empty stderr uses initialization fallback when provided`() {
        val error = MirrordError.fromStdErr("", MIRRORD_INITIALIZATION_UNKNOWN_ERROR)

        assertEquals(MIRRORD_INITIALIZATION_UNKNOWN_ERROR, error.richMessageForTest())
    }

    @Test
    fun `structured stderr keeps mirrord message and help`() {
        val error = MirrordError.fromStdErr(
            "Error: {" +
                "\"message\":\"target not found\"," +
                "\"severity\":\"error\"," +
                "\"causes\":[]," +
                "\"help\":\"check the target path\"," +
                "\"labels\":[]," +
                "\"related\":[]" +
                "}",
            MIRRORD_INITIALIZATION_UNKNOWN_ERROR
        )

        assertEquals("target not found", error.richMessageForTest())
        assertEquals("check the target path", error.helpForTest())
    }

    @Test
    fun `plain stderr remains the user-facing failure detail`() {
        val error = MirrordError.fromStdErr("deployment deploy/doesnotexist not found", MIRRORD_INITIALIZATION_UNKNOWN_ERROR)

        assertEquals("deployment deploy/doesnotexist not found", error.richMessageForTest())
    }

    @Test
    fun `already reported ide message suppresses fallback notification`() {
        val error = MirrordError.fromStdErr("", MIRRORD_INITIALIZATION_UNKNOWN_ERROR, alreadyReported = true)

        assertEquals("", error.richMessageForTest())
        assertEquals(null, error.helpForTest())
    }

    @Test
    fun `informational ide message does not suppress later process failure`() {
        val ideMessage = IdeMessage("queue_splitting_hint", NotificationLevel.Info, "Try queue splitting", emptySet())

        val error = MirrordError.fromStdErr(
            "deployment deploy/doesnotexist not found",
            MIRRORD_INITIALIZATION_UNKNOWN_ERROR,
            alreadyReported = ideMessage.isFailureNotification()
        )

        assertEquals("deployment deploy/doesnotexist not found", error.richMessageForTest())
    }

    @Test
    fun `informational ide message does not suppress empty stderr fallback`() {
        val ideMessage = IdeMessage("queue_splitting_hint", NotificationLevel.Info, "Try queue splitting", emptySet())

        val error = MirrordError.fromStdErr(
            "",
            MIRRORD_INITIALIZATION_UNKNOWN_ERROR,
            alreadyReported = ideMessage.isFailureNotification()
        )

        assertEquals(MIRRORD_INITIALIZATION_UNKNOWN_ERROR, error.richMessageForTest())
    }

    @Test
    fun `failure ide message still suppresses duplicate process failure`() {
        val ideMessage = IdeMessage("upgrade_cta", NotificationLevel.Warning, "mirrord operator was not found", emptySet())

        val error = MirrordError.fromStdErr(
            "operator not installed",
            MIRRORD_INITIALIZATION_UNKNOWN_ERROR,
            alreadyReported = ideMessage.isFailureNotification()
        )

        assertEquals("", error.richMessageForTest())
        assertEquals(null, error.helpForTest())
    }
}

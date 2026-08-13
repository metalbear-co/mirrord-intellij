package com.metalbear.mirrord

import com.google.gson.JsonParser
import com.intellij.execution.configurations.GeneralCommandLine
import com.metalbear.mirrord.bifrost.TargetPath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

/**
 * Tests for the Windows `pitm` env-ferrying payload and command-line wrap.
 *
 * Unlike [MirrordWslCharacterizationTest] these need no Windows host and no WSL — the payload
 * encoding and the command-line rewrite are pure functions. They run on every machine and in CI.
 *
 * This matters for the EEL migration for two reasons. `MIRRORD_CHILD_ENV` is an env-transport
 * mechanism that is independent of *where* the process runs, so it should survive the move to an
 * environment abstraction untouched — these tests are what proves that. And the wrap rewrites
 * `exePath` to the CLI path, which is one of the values that becomes environment-relative: today
 * both paths are host paths, and after the migration both must be target paths.
 */
class MirrordPitmEnvTest {

    private fun decodePayload(encoded: String) =
        JsonParser.parseString(String(Base64.getDecoder().decode(encoded))).asJsonObject

    // ---------------------------------------------------------------- payload encoding

    @Test
    fun `payload carries the variables the child should receive`() {
        val encoded = MirrordPitm.encodeChildEnv(
            mapOf(
                "MIRRORD_INTPROXY_ADDR" to "127.0.0.1:41234",
                "MIRRORD_RESOLVED_CONFIG" to """{"feature":{"fs":"read"}}"""
            ),
            envToUnset = null
        )

        val set = decodePayload(encoded).getAsJsonObject("set")
        assertEquals("127.0.0.1:41234", set.get("MIRRORD_INTPROXY_ADDR").asString)
        assertEquals("""{"feature":{"fs":"read"}}""", set.get("MIRRORD_RESOLVED_CONFIG").asString)
    }

    @Test
    fun `payload omits the unset key when there is nothing to unset`() {
        val nullCase = decodePayload(MirrordPitm.encodeChildEnv(mapOf("A" to "1"), envToUnset = null))
        val emptyCase = decodePayload(MirrordPitm.encodeChildEnv(mapOf("A" to "1"), envToUnset = emptyList()))

        assertFalse(nullCase.has("unset"), "unset must be absent, not null, when there is nothing to unset")
        assertFalse(emptyCase.has("unset"), "an empty unset list must not produce an empty array")
    }

    @Test
    fun `payload carries variables the child should drop`() {
        val encoded = MirrordPitm.encodeChildEnv(
            mapOf("MIRRORD_INTPROXY_ADDR" to "127.0.0.1:1"),
            envToUnset = listOf("DYLD_INSERT_LIBRARIES", "LD_PRELOAD")
        )

        val unset = decodePayload(encoded).getAsJsonArray("unset").map { it.asString }
        assertEquals(listOf("DYLD_INSERT_LIBRARIES", "LD_PRELOAD"), unset)
    }

    @Test
    fun `an empty environment still produces a well-formed payload`() {
        val payload = decodePayload(MirrordPitm.encodeChildEnv(emptyMap(), envToUnset = null))

        assertTrue(payload.has("set"), "the set object is always present, even when empty")
        assertTrue(payload.getAsJsonObject("set").entrySet().isEmpty())
    }

    // ---------------------------------------------------------------- command line wrap

    @Test
    fun `wrapping launches the original command through pitm`() {
        val commandLine = GeneralCommandLine("C:\\jdk\\bin\\java.exe")
            .withParameters("-jar", "app.jar", "--port", "8080")

        MirrordPitm.wrapCommandLine(
            commandLine,
            cliPath = TargetPath("C:\\plugins\\mirrord\\bin\\windows\\x86-64\\mirrord.exe"),
            mirrordEnvVars = mapOf("MIRRORD_INTPROXY_ADDR" to "127.0.0.1:1"),
            envToUnset = null
        )

        assertEquals("C:\\plugins\\mirrord\\bin\\windows\\x86-64\\mirrord.exe", commandLine.exePath)
        assertEquals(
            listOf("pitm", "--", "C:\\jdk\\bin\\java.exe", "-jar", "app.jar", "--port", "8080"),
            commandLine.parametersList.list
        )
    }

    /**
     * The wrapper process must not inherit the layer's environment — only the child does, via the
     * payload. If a mirrord variable stayed on the `mirrord.exe pitm` process itself, that process
     * would try to load the layer into the wrapper.
     */
    @Test
    fun `wrapping moves mirrord variables off the wrapper and into the payload`() {
        val commandLine = GeneralCommandLine("app.exe")
        commandLine.withEnvironment("MIRRORD_INTPROXY_ADDR", "127.0.0.1:41234")
        commandLine.withEnvironment("PATH", "C:\\Windows\\System32")

        MirrordPitm.wrapCommandLine(
            commandLine,
            cliPath = TargetPath("mirrord.exe"),
            mirrordEnvVars = mapOf("MIRRORD_INTPROXY_ADDR" to "127.0.0.1:41234"),
            envToUnset = null
        )

        assertFalse(
            commandLine.environment.containsKey("MIRRORD_INTPROXY_ADDR"),
            "mirrord vars must be stripped from the wrapper's own environment"
        )
        assertEquals(
            "C:\\Windows\\System32",
            commandLine.environment["PATH"],
            "unrelated variables must survive the wrap"
        )

        val payload = decodePayload(commandLine.environment.getValue(MirrordPitm.CHILD_ENV_VAR))
        assertEquals(
            "127.0.0.1:41234",
            payload.getAsJsonObject("set").get("MIRRORD_INTPROXY_ADDR").asString
        )
    }
}

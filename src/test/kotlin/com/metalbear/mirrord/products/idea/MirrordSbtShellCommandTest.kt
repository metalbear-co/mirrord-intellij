package com.metalbear.mirrord.products.idea

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MirrordSbtShellCommandTest {
    private fun build(
        commands: String,
        configurationEnv: Map<String, String> = emptyMap(),
        mirrordEnv: Map<String, String> = mapOf("MIRRORD_CONFIG" to "/tmp/c.json"),
        envToUnset: Set<String> = emptySet()
    ) = buildMirrordSbtShellCommands(commands, configurationEnv, mirrordEnv, envToUnset)

    @Test
    fun `forks the task and sets its env, then runs the original command`() {
        assertEquals(
            ";set Compile / run / fork := true" +
                ";set Compile / run / envVars := " +
                "Map(\"MIRRORD_CONFIG\" -> \"/tmp/c.json\", \"MIRRORD_DETECT_DEBUGGER_PORT\" -> \"javaagent\")" +
                ";run",
            build("run")
        )
    }

    @Test
    fun `marks the forked JVM debuggable through the java agent`() {
        // Without this the debugger port is not detected and debugging an SBT run silently misbehaves.
        assertTrue(build("run")!!.contains("\"MIRRORD_DETECT_DEBUGGER_PORT\" -> \"javaagent\""))
    }

    @Test
    fun `lets mirrord's variables win over the configuration's`() {
        val result = build(
            "run",
            configurationEnv = mapOf("SHARED" to "from-config"),
            mirrordEnv = mapOf("SHARED" to "from-mirrord")
        )!!
        assertTrue(result.contains("\"SHARED\" -> \"from-mirrord\""))
        assertTrue(!result.contains("from-config"))
    }

    @Test
    fun `drops variables mirrord asks to unset, even ones the user set`() {
        val result = build(
            "run",
            configurationEnv = mapOf("DROP_ME" to "user-value", "KEEP" to "kept"),
            mirrordEnv = mapOf("DROP_ME" to "mirrord-value"),
            envToUnset = setOf("DROP_ME")
        )!!
        assertTrue(!result.contains("DROP_ME"))
        assertTrue(result.contains("\"KEEP\" -> \"kept\""))
    }

    @Test
    fun `orders the env map by key so the command is deterministic`() {
        val result = build("run", configurationEnv = mapOf("ZED" to "z", "ALPHA" to "a"))!!
        assertTrue(result.indexOf("\"ALPHA\"") < result.indexOf("\"ZED\""))
    }

    @Test
    fun `escapes quotes and backslashes so the literal stays parseable`() {
        val result = build("run", configurationEnv = mapOf("Q" to """a"b\c"""))!!
        assertTrue(result.contains("\"Q\" -> \"a\\\"b\\\\c\""))
    }

    @Test
    fun `escapes newlines and tabs rather than breaking the command across lines`() {
        val result = build("run", configurationEnv = mapOf("N" to "a\nb\tc"))!!
        assertTrue(result.contains("""a\nb\tc"""))
    }

    @Test
    fun `scopes runMain to Compile`() {
        assertTrue(build("runMain com.Example")!!.startsWith(";set Compile / runMain / fork := true"))
    }

    @Test
    fun `keeps a project-qualified task as its own scope`() {
        assertTrue(build("backend/run")!!.startsWith(";set backend/run / fork := true"))
    }

    @Test
    fun `scopes fgRun without a Compile prefix`() {
        assertTrue(build("fgRun")!!.startsWith(";set fgRun / fork := true"))
    }

    @Test
    fun `declines an already chained command`() {
        // Several commands have no single scope to attach fork and envVars to.
        assertNull(build(";clean ;run"))
    }

    @Test
    fun `declines a task it does not recognise`() {
        assertNull(build("test"))
    }

    @Test
    fun `declines an empty command`() {
        assertNull(build(""))
    }
}

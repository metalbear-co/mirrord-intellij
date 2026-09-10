package com.metalbear.mirrord.products.idea

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MirrordSbtTaskPreprocessingTest {
    @Test
    fun `passes the raw string through when the SBT shell is off`() {
        // Without the shell the string reaches the launcher as typed; chaining would corrupt it.
        assertEquals("clean compile run", preprocessSbtTasks("clean compile run", useSbtShell = false))
    }

    @Test
    fun `leaves an already chained command list alone`() {
        assertEquals(";clean ;run", preprocessSbtTasks(";clean ;run", useSbtShell = true))
    }

    @Test
    fun `ignores leading whitespace when spotting an already chained list`() {
        assertEquals("  ;clean ;run", preprocessSbtTasks("  ;clean ;run", useSbtShell = true))
    }

    @Test
    fun `returns a single command unchained`() {
        // The leading `;` is what tells SBT a list follows, so one command must not get one.
        assertEquals("run", preprocessSbtTasks("run", useSbtShell = true))
    }

    @Test
    fun `chains several commands`() {
        assertEquals(";clean ;compile ;run", preprocessSbtTasks("clean compile run", useSbtShell = true))
    }

    @Test
    fun `splits a quoted argument into its own command, as the Scala plugin did`() {
        assertEquals(";runMain ;com.Example arg", preprocessSbtTasks("runMain \"com.Example arg\"", useSbtShell = true))
    }

    @Test
    fun `turns an empty task list into a bare separator`() {
        assertEquals(";", preprocessSbtTasks("", useSbtShell = true))
    }
}

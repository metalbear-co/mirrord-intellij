package com.metalbear.mirrord

import com.intellij.execution.process.ProcessOutput
import com.metalbear.mirrord.bifrost.FakeMirrordEnvironment
import com.metalbear.mirrord.bifrost.TargetPath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.io.IOException

class MirrordConfigSchemaTest {
    private val binary = TargetPath("/usr/bin/mirrord")

    private val schema = """{"${'$'}schema": "http://json-schema.org/draft-07/schema#", "title": "LayerFileConfig"}"""

    @Test
    fun `the schema is what the binary prints with print-schema`() {
        val environment = FakeMirrordEnvironment().apply {
            probeAnswer = { args ->
                if (args == listOf("print-schema")) {
                    ProcessOutput(0).apply { appendStdout(schema) }
                } else {
                    ProcessOutput(2).apply { appendStderr("unexpected arguments $args") }
                }
            }
        }

        val source = configSchemaOf(binary, environment)

        assertEquals(schema, assertInstanceOf(MirrordConfigSchemaSource.Binary::class.java, source).schema)
    }

    @Test
    fun `a binary without print-schema falls back to GitHub`() {
        // What mirrord 3.256.0 prints for a command that it does not know.
        val environment = FakeMirrordEnvironment().apply {
            probeAnswer = {
                ProcessOutput(2).apply { appendStderr("error: unrecognized subcommand 'print-schema'") }
            }
        }

        assertSame(MirrordConfigSchemaSource.GitHub, configSchemaOf(binary, environment))
    }

    @Test
    fun `a binary that cannot run gives no schema`() {
        val environment = FakeMirrordEnvironment().apply {
            probeAnswer = { throw IOException("Cannot run program \"/usr/bin/mirrord\": error=13, Permission denied") }
        }

        assertSame(MirrordConfigSchemaSource.None, configSchemaOf(binary, environment))
    }

    @Test
    fun `a binary that does not finish in time gives no schema`() {
        // Some environments report the timeout in the output, and do not throw.
        val environment = FakeMirrordEnvironment().apply {
            probeAnswer = { ProcessOutput().apply { setTimeout() } }
        }

        assertSame(MirrordConfigSchemaSource.None, configSchemaOf(binary, environment))
    }
}

package com.metalbear.mirrord.bifrost

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * The resolution chain replaces the same four-line `when` that was copy-pasted across eleven
 * product files. These tests are what make that trade worthwhile: the decision is now in one
 * place and can be exercised without an IDE.
 */
class MirrordEnvironmentSourceTest {

    private fun context(
        label: String = "test",
        forced: MirrordEnvironment? = null
    ) = MirrordLaunchContext(
        label = label,
        targetRequest = null,
        forced = forced,
        project = null,
        descriptorSupplier = { null }
    )

    private fun source(id: String, result: MirrordEnvironment?) = object : MirrordEnvironmentSource {
        override val id = id
        override fun resolve(context: MirrordLaunchContext): MirrordEnvironment? = result
    }

    @Test
    fun `first source to answer wins`() {
        val first = FakeMirrordEnvironment("first")
        val second = FakeMirrordEnvironment("second")

        val resolved = MirrordEnvironments.resolve(
            context(),
            listOf(source("a", null), source("b", first), source("c", second))
        )

        assertSame(first, resolved, "a later source must not override an earlier one")
    }

    @Test
    fun `a source that throws is skipped rather than fatal`() {
        val fallback = FakeMirrordEnvironment("fallback")
        val exploding = object : MirrordEnvironmentSource {
            override val id = "exploding"
            override fun resolve(context: MirrordLaunchContext): MirrordEnvironment =
                throw IllegalStateException("boom")
        }

        val resolved = MirrordEnvironments.resolve(context(), listOf(exploding, source("ok", fallback)))

        // One environment kind failing to introspect itself must not stop mirrord from running
        // at all — it should fall through to something that works.
        assertSame(fallback, resolved)
    }

    @Test
    fun `forced environment beats everything`() {
        val forced = FakeMirrordEnvironment("forced")
        val other = FakeMirrordEnvironment("other")

        val resolved = MirrordEnvironments.resolve(
            context(forced = forced),
            listOf(ForcedEnvironmentSource(), source("other", other))
        )

        assertSame(forced, resolved)
    }

    @Test
    fun `legacy wsl declines when the opt-out is off`() {
        val source = LegacyWslEnvironmentSource(legacyEnabled = { false })

        assertNull(source.resolve(context()))
    }

    @Test
    fun `legacy wsl declines when the launch is not wsl even if the opt-out is on`() {
        // The setting must be inert outside WSL. A user who turns it on to work around a WSL
        // problem should not thereby change how their dev container behaves.
        val source = LegacyWslEnvironmentSource(legacyEnabled = { true })

        assertNull(source.resolve(context()))
    }

    @Test
    fun `target request source declines when there is no request`() {
        assertNull(TargetRequestEnvironmentSource().resolve(context()))
    }

    @Test
    fun `project descriptor source declines when the project has no descriptor`() {
        assertNull(ProjectDescriptorEnvironmentSource().resolve(context()))
    }

    @Test
    fun `wslDistributionOf is null for a non-wsl request`() {
        assertNull(wslDistributionOf(null))
    }

    @Test
    fun `the chain is total`() {
        // Every source declining must still produce an environment: callers never handle null,
        // and "mirrord silently did nothing" is the failure mode this whole change exists to end.
        val resolved = MirrordEnvironments.resolve(
            context(),
            listOf(source("a", null), source("b", null), LocalEnvironmentSource())
        )

        assertEquals(true, resolved.isLocal)
    }
}

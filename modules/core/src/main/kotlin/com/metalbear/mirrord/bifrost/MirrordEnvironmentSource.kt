@file:Suppress("UnstableApiUsage")

package com.metalbear.mirrord.bifrost

import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.target.EelTargetEnvironmentRequest
import com.intellij.execution.target.TargetEnvironmentRequest
import com.intellij.execution.target.createEnvironmentRequest
import com.intellij.execution.wsl.WSLDistribution
import com.intellij.execution.wsl.target.WslTargetEnvironmentRequest
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.platform.eel.EelDescriptor
import com.intellij.platform.eel.provider.LocalEelDescriptor
import com.intellij.platform.eel.provider.getEelDescriptor
import com.metalbear.mirrord.MirrordError
import com.metalbear.mirrord.MirrordLogger
import com.metalbear.mirrord.MirrordSettingsState
import java.nio.file.Path

/**
 * Everything a source is allowed to look at when deciding where mirrord runs.
 *
 * The project-derived descriptor is a function rather than a field, so the resolution chain runs
 * in a plain unit test with no IDE and no open project.
 */
class MirrordLaunchContext(
    val label: String,
    val targetRequest: TargetEnvironmentRequest?,
    val forced: MirrordEnvironment?,
    internal val project: Project?,
    private val descriptorSupplier: () -> EelDescriptor?
) {
    constructor(project: Project, targetRequest: TargetEnvironmentRequest? = null, forced: MirrordEnvironment? = null) :
        this(project.name, targetRequest, forced, project, { project.getEelDescriptor() })

    fun projectDescriptor(): EelDescriptor? = descriptorSupplier()
}

/** One way of discovering where mirrord should run. */
interface MirrordEnvironmentSource {
    val id: String

    /** Returns null to defer to the next source. */
    fun resolve(context: MirrordLaunchContext): MirrordEnvironment?
}

/** Pulls a [WSLDistribution] out of a target request, if that is what it is. */
internal fun wslDistributionOf(request: TargetEnvironmentRequest?): WSLDistribution? =
    (request as? WslTargetEnvironmentRequest)?.configuration?.distribution

/** Uses the environment the caller passed in, if it passed one. */
class ForcedEnvironmentSource : MirrordEnvironmentSource {
    override val id = "forced"
    override fun resolve(context: MirrordLaunchContext): MirrordEnvironment? = context.forced
}

/**
 * Declines unless the user asked for legacy WSL *and* this is a WSL launch, so turning the
 * setting on cannot affect anything else.
 */
class LegacyWslEnvironmentSource(
    private val legacyEnabled: () -> Boolean = { MirrordSettingsState.instance.mirrordState.useLegacyWsl }
) : MirrordEnvironmentSource {
    override val id = "legacy-wsl"

    override fun resolve(context: MirrordLaunchContext): MirrordEnvironment? {
        if (!legacyEnabled()) return null
        val distribution = wslDistributionOf(context.targetRequest) ?: return null
        return LegacyWslEnvironment(distribution, context.project)
    }
}

/**
 * The run configuration's own target, which is the most specific answer available: one project
 * can hold configurations aimed at different environments.
 */
class TargetRequestEnvironmentSource : MirrordEnvironmentSource {
    override val id = "target-request"

    override fun resolve(context: MirrordLaunchContext): MirrordEnvironment? {
        val request = context.targetRequest ?: return null

        // The modern shape; WslTargetEnvironmentRequest is deprecated in favour of it.
        (request as? EelTargetEnvironmentRequest)?.configuration?.descriptor?.let {
            return EelEnvironment(it)
        }

        // WSL through EEL rather than wsl.exe. The platform maps the UNC root back to the
        // distribution's descriptor.
        //
        // `getUNCRootPath` stays a method call: two leading capitals mean Kotlin never
        // decapitalises the name into a property.
        wslDistributionOf(request)?.let { distribution ->
            @Suppress("DEPRECATION")
            val uncRoot: Path? = runCatching { distribution.getUNCRootPath() }.getOrNull()
            uncRoot?.let { return EelEnvironment(it.getEelDescriptor()) }
        }

        return null
    }
}

/**
 * Where the project itself lives.
 *
 * This is what makes native dev containers work: the project directory is inside the container,
 * so the platform hands back the container's descriptor.
 *
 * It is also the right answer for Rider, whose extension point never sees the run configuration
 * and has nothing more specific to go on.
 */
class ProjectDescriptorEnvironmentSource : MirrordEnvironmentSource {
    override val id = "project-descriptor"

    override fun resolve(context: MirrordLaunchContext): MirrordEnvironment? {
        val descriptor = runCatching { context.projectDescriptor() }.getOrNull() ?: return null
        if (descriptor === LocalEelDescriptor) return null
        return EelEnvironment(descriptor)
    }
}

/** Always resolves, so the chain is total and callers never handle null. */
class LocalEnvironmentSource : MirrordEnvironmentSource {
    override val id = "local"
    override fun resolve(context: MirrordLaunchContext): MirrordEnvironment = EelEnvironment(LocalEelDescriptor)
}

/**
 * Resolves where mirrord runs, by asking each source in turn.
 *
 * The order is data in one place, so a new kind of environment is a new source rather than
 * another branch in a `when`.
 *
 * The winner is logged at INFO, because which environment was picked is not visible from
 * outside the plugin.
 */
object MirrordEnvironments {
    internal val defaultSources: List<MirrordEnvironmentSource> = listOf(
        ForcedEnvironmentSource(),
        LegacyWslEnvironmentSource(),
        TargetRequestEnvironmentSource(),
        ProjectDescriptorEnvironmentSource(),
        LocalEnvironmentSource()
    )

    fun resolve(context: MirrordLaunchContext): MirrordEnvironment = resolve(context, defaultSources)

    /**
     * Where the project lives. For extension points that never see a run configuration.
     *
     * This and the two below build the launch context in one place per shape, so no call site
     * spells out `resolve(MirrordLaunchContext(...))`.
     */
    fun forProject(project: Project): MirrordEnvironment = resolve(MirrordLaunchContext(project))

    /** For a caller that already holds the run configuration's target request. */
    fun forRequest(project: Project, request: TargetEnvironmentRequest?): MirrordEnvironment =
        resolve(MirrordLaunchContext(project, request))

    /** For an extension point that has the run profile but must build the request itself. */
    fun forRunProfile(project: Project, profile: RunProfile): MirrordEnvironment =
        resolve(MirrordLaunchContext(project, createEnvironmentRequest(profile, project)))

    internal fun resolve(context: MirrordLaunchContext, sources: List<MirrordEnvironmentSource>): MirrordEnvironment {
        val failures = mutableListOf<Pair<String, Throwable>>()

        for (source in sources) {
            val environment = try {
                source.resolve(context)
            } catch (e: ProcessCanceledException) {
                // Cancellation is control flow. Swallowing it turns Cancel into a confusing
                // error about environment resolution.
                throw e
            } catch (e: Throwable) {
                MirrordLogger.logger.warn("mirrord.bifrost: source=${source.id} threw, skipping", e)
                failures += source.id to e
                null
            }

            if (environment != null) {
                MirrordLogger.logger.info(
                    "mirrord.bifrost: resolved via=${source.id} env=${environment.name} " +
                        "local=${environment.isLocal} for=${context.label}"
                )
                return environment
            }
            MirrordLogger.logger.debug("mirrord.bifrost: source=${source.id} declined for=${context.label}")
        }

        // The last source always returns an environment, so reaching here means it threw.
        // Report what went wrong rather than an "unreachable" that sends the reader hunting for
        // a logic bug in the chain.
        val cause = failures.lastOrNull()
        throw MirrordError(
            "mirrord could not determine where to run.",
            failures.joinToString("; ") { (id, e) -> "$id: ${e.message ?: e::class.java.simpleName}" }
                .ifEmpty { "No environment source produced a result." },
            cause?.second
        )
    }
}

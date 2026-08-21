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
 * Everything a source is allowed to look at when deciding which end of the bridge to aim for.
 *
 * The project-derived descriptor sits behind a function rather than being a field so the whole
 * resolution chain can be exercised in a plain unit test, with no IDE and no open project.
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

/**
 * Pulls a [WSLDistribution] out of a target request, if that is what it is.
 *
 * One null-safe expression, in one place. This replaces the same four-line `when` copy-pasted
 * across eleven product files, seven of which ended it with `!!`.
 */
internal fun wslDistributionOf(request: TargetEnvironmentRequest?): WSLDistribution? =
    (request as? WslTargetEnvironmentRequest)?.configuration?.distribution

/** Test seam, and a way to force an environment without a real container. */
class ForcedEnvironmentSource : MirrordEnvironmentSource {
    override val id = "forced"
    override fun resolve(context: MirrordLaunchContext): MirrordEnvironment? = context.forced
}

/**
 * The opt-out. Declines unless the user has asked for legacy WSL *and* this really is a WSL
 * launch, so turning the setting on cannot affect anything else.
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

        // The modern shape. WslTargetEnvironmentRequest is deprecated in favour of this.
        (request as? EelTargetEnvironmentRequest)?.configuration?.descriptor?.let {
            return EelEnvironment(it)
        }

        // WSL, reached through EEL rather than wsl.exe. The UNC root is a path the platform
        // already knows how to map back to the distribution's descriptor.
        //
        // `getUNCRootPath` stays a method call: Kotlin does not synthesise a property for it,
        // because two leading capitals mean the name never gets decapitalised.
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
 * This is the one that makes native dev containers work: the project directory is inside the
 * container, so the platform hands back the container's descriptor. It is also the right answer
 * for Rider, whose extension point never sees the run configuration being launched and so has
 * nothing more specific to go on.
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
 * The order is data, reviewable in one place, and a new kind of environment is a new source
 * rather than another branch in a growing `when`. The winner is logged at INFO, so a
 * misresolution is one grep rather than a debugging session — which matters because "which
 * environment did it pick" is not otherwise visible from the outside.
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
     * These three entry points exist so the launch context is built in exactly one place per
     * shape. Spelling `resolve(MirrordLaunchContext(...))` at each call site was the same
     * copy-paste that [wslDistributionOf] just removed, one layer up.
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
                // Cancellation is control flow, not a failure. Swallowing it here would turn a
                // user pressing Cancel into a confusing error about environment resolution.
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

        // The last source always returns an environment, so getting here means it threw. Report
        // what actually went wrong rather than an "unreachable" that sends the reader hunting
        // for a logic bug in the chain — the first time this fired, the real cause was a missing
        // module dependency in plugin.xml and the message pointed nowhere near it.
        val cause = failures.lastOrNull()
        throw MirrordError(
            "mirrord could not determine where to run.",
            failures.joinToString("; ") { (id, e) -> "$id: ${e.message ?: e::class.java.simpleName}" }
                .ifEmpty { "No environment source produced a result." },
            cause?.second
        )
    }
}

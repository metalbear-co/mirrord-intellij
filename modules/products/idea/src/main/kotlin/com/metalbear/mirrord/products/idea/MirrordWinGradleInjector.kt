package com.metalbear.mirrord.products.idea

import com.intellij.notification.NotificationType
import com.intellij.openapi.components.service
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemRunConfiguration
import com.metalbear.mirrord.MirrordBinaryManager
import com.metalbear.mirrord.MirrordLogger
import com.metalbear.mirrord.MirrordPitm
import com.metalbear.mirrord.MirrordProjectService
import com.metalbear.mirrord.bifrost.MirrordEnvironments
import com.metalbear.mirrord.bifrost.MirrordLaunchContext
import java.io.File
import java.util.Base64

/**
 * Windows-native Gradle layer injection: generates the init script that arranges
 * for the mirrord layer to end up in the forked user JVM. There is no `LD_PRELOAD`
 * on Windows, so the injected script replaces each matched `JavaExec` task's
 * built-in action with `mirrord pitm -- <java> @<argfile>`, launching the JVM
 * suspended so the layer DLL is injected before it resumes.
 *
 * This covers both Run and Debug. In Debug the forked JVM keeps IntelliJ's
 * dispatched `-agentlib:jdwp` argument (the init script merges Gradle's effective
 * and direct JVM arguments), so the debugger still attaches to the now-injected JVM. Debug does
 * not use `mirrord attach` here — that path exists only in Rider, where the
 * debugger, not Gradle, owns process creation.
 *
 * Kept separate from [IdeaRunConfigurationExtension] so the Groovy template
 * lives as a standalone resource (with real syntax highlighting) rather than a
 * multi-line Kotlin string riddled with `$` / `\\` escapes.
 *
 * Task selection is authoritative from Gradle itself: the init script matches
 * against `gradle.startParameter.taskNames`, which covers IntelliJ's
 * synthesized `:<fqcn>.main()` tasks, the `application` plugin's `run`,
 * qualified subproject tasks, and any user-defined JavaExec invoked from the
 * IDE. No task names flow from the Kotlin side.
 *
 * Call [wrap] once per run configuration in `updateJavaParameters` when on
 * Windows-native Gradle. See the caller for the `isWinNative` gating.
 */
internal object MirrordWinGradleInjector {
    private const val TEMPLATE_RESOURCE = "mirrord-win-gradle-init.gradle.template"

    private const val PLACEHOLDER_CLI_PATH_BASE64 = "__MIRRORD_CLI_PATH_BASE64__"
    private const val PLACEHOLDER_CHILD_ENV_VAR = "__MIRRORD_CHILD_ENV_VAR__"
    private const val PLACEHOLDER_DEBUG_EXPECTED = "__MIRRORD_DEBUG_EXPECTED__"

    /**
     * Generates the init script for [configuration], appends it to the Gradle
     * `--init-script` chain, and adds [MirrordPitm.CHILD_ENV_VAR] to the
     * configuration's environment. Mirrord env vars reach the user JVM via that
     * single base64-JSON payload, which `mirrord pitm` decodes — the Gradle
     * daemon itself never sees them.
     *
     * Fails the launch if the init script cannot be created, so enabling mirrord can
     * never silently run the application without the layer.
     */
    fun wrap(
        configuration: ExternalSystemRunConfiguration,
        mirrordEnvVars: Map<String, String>,
        envToUnset: List<String>?,
        debugExpected: Boolean
    ) {
        MirrordLogger.logger.info(
            "MirrordWinGradleInjector.wrap: ENTER taskNames=${configuration.settings.taskNames} " +
                "mirrordEnvVars=${mirrordEnvVars.size} envToUnset=${envToUnset?.size ?: 0} " +
                "debugExpected=$debugExpected"
        )

        val project = configuration.project
        val cliPath = service<MirrordBinaryManager>()
            .getCliPath("idea", MirrordEnvironments.resolve(MirrordLaunchContext(project)), project)
            .value
            .replace("\\", "/")
        val childEnvPayload = MirrordPitm.encodeChildEnv(mirrordEnvVars, envToUnset)

        MirrordLogger.logger.info(
            "MirrordWinGradleInjector.wrap: cliPathPresent=${cliPath.isNotBlank()} " +
                "childEnvPayload.len=${childEnvPayload.length}"
        )

        val initScript = try {
            writeInitScript(cliPath, debugExpected)
        } catch (e: Exception) {
            MirrordLogger.logger.warn("MirrordWinGradleInjector.wrap: failed to create init script: ${e.message}", e)
            project.service<MirrordProjectService>().notifier.notifySimple(
                "mirrord: could not create the Gradle init script (${e.message}); layer will not load.",
                NotificationType.ERROR
            )
            throw IllegalStateException("mirrord could not create the Gradle init script", e)
        }

        val envBefore = configuration.settings.env.size
        val scriptParamsBefore = configuration.settings.scriptParameters ?: ""
        configuration.settings.env = configuration.settings.env + mapOf(
            MirrordPitm.CHILD_ENV_VAR to childEnvPayload
        )
        appendInitScript(configuration, initScript)

        MirrordLogger.logger.info(
            "MirrordWinGradleInjector.wrap: SUCCESS initScript=${initScript.absolutePath} " +
                "size=${initScript.length()}b, " +
                "settings.env $envBefore → ${configuration.settings.env.size}, " +
                "scriptParameters grew from ${scriptParamsBefore.length} to " +
                "${(configuration.settings.scriptParameters ?: "").length} chars"
        )
    }

    /** Loads the template, substitutes placeholders, writes to a temp file. */
    private fun writeInitScript(cliPath: String, debugExpected: Boolean): File {
        val template = loadTemplate()
        val groovy = template
            .replace(
                PLACEHOLDER_CLI_PATH_BASE64,
                Base64.getEncoder().encodeToString(cliPath.toByteArray(Charsets.UTF_8))
            )
            .replace(PLACEHOLDER_CHILD_ENV_VAR, MirrordPitm.CHILD_ENV_VAR)
            .replace(PLACEHOLDER_DEBUG_EXPECTED, debugExpected.toString())
        return File.createTempFile("mirrord-win-gradle-", ".gradle").apply {
            deleteOnExit()
            writeText(groovy)
        }
    }

    private fun loadTemplate(): String {
        val stream = javaClass.getResourceAsStream(TEMPLATE_RESOURCE)
            ?: error("mirrord: $TEMPLATE_RESOURCE missing from plugin jar")
        return stream.bufferedReader().use { it.readText() }
    }

    /** Appends `--init-script <path>` to the Gradle run configuration's script parameters. */
    private fun appendInitScript(configuration: ExternalSystemRunConfiguration, script: File) {
        val scriptPath = script.absolutePath.replace("\\", "/")
        val existing = configuration.settings.scriptParameters ?: ""
        configuration.settings.scriptParameters = "$existing --init-script \"$scriptPath\""
    }
}

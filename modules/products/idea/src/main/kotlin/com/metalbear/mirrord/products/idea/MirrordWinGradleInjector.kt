package com.metalbear.mirrord.products.idea

import com.intellij.notification.NotificationType
import com.intellij.openapi.components.service
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemRunConfiguration
import com.metalbear.mirrord.MirrordBinaryManager
import com.metalbear.mirrord.MirrordLogger
import com.metalbear.mirrord.MirrordPitm
import com.metalbear.mirrord.MirrordProjectService
import java.io.File

/**
 * Windows-native Gradle layer injection: generates the init script that arranges
 * for the mirrord layer to end up in the forked user JVM. There is no `LD_PRELOAD`
 * on Windows, so the injected script replaces each matched `JavaExec` task's
 * built-in action with `mirrord pitm -- <java> @<argfile>`, launching the JVM
 * suspended so the layer DLL is injected before it resumes.
 *
 * This covers both Run and Debug. In Debug the forked JVM keeps IntelliJ's
 * dispatched `-agentlib:jdwp` argument (pitm forwards `task.allJvmArgs`
 * verbatim), so the debugger still attaches to the now-injected JVM. Debug does
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

    private const val PLACEHOLDER_CLI_PATH = "__MIRRORD_CLI_PATH__"
    private const val PLACEHOLDER_CHILD_ENV_VAR = "__MIRRORD_CHILD_ENV_VAR__"

    /**
     * Generates the init script for [configuration], appends it to the Gradle
     * `--init-script` chain, and adds [MirrordPitm.CHILD_ENV_VAR] to the
     * configuration's environment. Mirrord env vars reach the user JVM via that
     * single base64-JSON payload, which `mirrord pitm` decodes — the Gradle
     * daemon itself never sees them.
     *
     * On failure to create the init script, falls back to setting [mirrordEnvVars]
     * directly on the configuration, so the Gradle run still executes (without the
     * layer) rather than failing outright.
     */
    fun wrap(
        configuration: ExternalSystemRunConfiguration,
        mirrordEnvVars: Map<String, String>,
        envToUnset: List<String>?
    ) {
        MirrordLogger.logger.info(
            "MirrordWinGradleInjector.wrap: ENTER taskNames=${configuration.settings.taskNames} " +
                "mirrordEnvVars=${mirrordEnvVars.size} envToUnset=${envToUnset?.size ?: 0}"
        )

        val project = configuration.project
        val cliPath = service<MirrordBinaryManager>()
            .getCliPath("idea", null, project)
            .replace("\\", "/")
        val childEnvPayload = MirrordPitm.encodeChildEnv(mirrordEnvVars, envToUnset)

        MirrordLogger.logger.info(
            "MirrordWinGradleInjector.wrap: cliPath=$cliPath childEnvPayload.len=${childEnvPayload.length}"
        )

        val initScript = try {
            writeInitScript(cliPath)
        } catch (e: Exception) {
            MirrordLogger.logger.warn("MirrordWinGradleInjector.wrap: failed to create init script: ${e.message}", e)
            project.service<MirrordProjectService>().notifier.notifySimple(
                "mirrord: could not create the Gradle init script (${e.message}); layer will not load.",
                NotificationType.ERROR
            )
            configuration.settings.env =
                configuration.settings.env + mirrordEnvVars - envToUnset.orEmpty().toSet()
            return
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
    private fun writeInitScript(cliPath: String): File {
        val template = loadTemplate()
        val groovy = template
            .replace(PLACEHOLDER_CLI_PATH, cliPath)
            .replace(PLACEHOLDER_CHILD_ENV_VAR, MirrordPitm.CHILD_ENV_VAR)
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

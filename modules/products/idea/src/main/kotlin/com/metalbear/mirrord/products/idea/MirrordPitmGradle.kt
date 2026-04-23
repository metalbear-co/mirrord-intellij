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
 * Windows-native Gradle wrapping: generates a Gradle init script that replaces
 * `JavaExec`'s built-in action with `mirrord pitm -- <java> @<argfile>` so the
 * user JVM launches suspended for layer injection.
 *
 * Kept separate from [IdeaRunConfigurationExtension] so the Groovy template
 * lives as a standalone resource (with real syntax highlighting) rather than a
 * multi-line Kotlin string riddled with `$` / `\\` escapes.
 *
 * Call [wrap] once per run configuration in `updateJavaParameters` when on
 * Windows-native + non-debug Gradle. See caller for the isWinNative / !isDebug
 * gating.
 */
internal object MirrordPitmGradle {
    private const val TEMPLATE_RESOURCE = "mirrord-pitm-init.gradle.template"

    private const val PLACEHOLDER_CLI_PATH = "__MIRRORD_CLI_PATH__"
    private const val PLACEHOLDER_TASK_FILTER = "__MIRRORD_TASK_FILTER__"
    private const val PLACEHOLDER_CHILD_ENV_VAR = "__MIRRORD_CHILD_ENV_VAR__"

    /**
     * Generates the pitm init script for [configuration], appends it to the
     * Gradle `--init-script` chain, and adds [MirrordPitm.CHILD_ENV_VAR] to
     * the configuration's environment. Mirrord env vars reach the user JVM via
     * that single base64-JSON payload — the Gradle daemon itself does not see
     * them.
     *
     * On failure to create the init script, falls back to setting [mirrordEnvVars]
     * directly on the configuration (no pitm), so the Gradle run still executes
     * without mirrord rather than failing outright.
     */
    fun wrap(
        configuration: ExternalSystemRunConfiguration,
        mirrordEnvVars: Map<String, String>,
        envToUnset: List<String>?
    ) {
        MirrordLogger.logger.info(
            "MirrordPitmGradle.wrap: ENTER taskNames=${configuration.settings.taskNames} " +
                "mirrordEnvVars=${mirrordEnvVars.size} envToUnset=${envToUnset?.size ?: 0}"
        )

        val project = configuration.project
        val cliPath = service<MirrordBinaryManager>()
            .getCliPath("idea", null, project)
            .replace("\\", "/")
        val childEnvPayload = MirrordPitm.encodeChildEnv(mirrordEnvVars, envToUnset)
        val taskFilter = groovyTaskNameFilter(configuration.settings.taskNames)

        MirrordLogger.logger.info(
            "MirrordPitmGradle.wrap: cliPath=$cliPath taskFilter=[$taskFilter] " +
                "childEnvPayload.len=${childEnvPayload.length}"
        )
        if (taskFilter.isBlank()) {
            MirrordLogger.logger.warn(
                "MirrordPitmGradle.wrap: taskFilter is empty — init script will match no tasks. " +
                    "This usually means the Gradle config has no taskNames."
            )
            project.service<MirrordProjectService>().notifier.notifySimple(
                "mirrord: Gradle config has no task names; pitm wrap will not match any task",
                NotificationType.WARNING
            )
        }

        val initScript = try {
            writeInitScript(cliPath, taskFilter)
        } catch (e: Exception) {
            MirrordLogger.logger.warn("MirrordPitmGradle.wrap: failed to create init script: ${e.message}", e)
            project.service<MirrordProjectService>().notifier.notifySimple(
                "mirrord: could not create pitm init script (${e.message}); layer will not load.",
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
            "MirrordPitmGradle.wrap: SUCCESS initScript=${initScript.absolutePath} " +
                "size=${initScript.length()}b, " +
                "settings.env $envBefore → ${configuration.settings.env.size}, " +
                "scriptParameters grew from ${scriptParamsBefore.length} to " +
                "${(configuration.settings.scriptParameters ?: "").length} chars"
        )
    }

    /** Loads the template, substitutes placeholders, writes to a temp file. */
    private fun writeInitScript(cliPath: String, taskFilter: String): File {
        val template = loadTemplate()
        val groovy = template
            .replace(PLACEHOLDER_CLI_PATH, cliPath)
            .replace(PLACEHOLDER_TASK_FILTER, taskFilter)
            .replace(PLACEHOLDER_CHILD_ENV_VAR, MirrordPitm.CHILD_ENV_VAR)
        return File.createTempFile("mirrord-pitm-", ".gradle").apply {
            deleteOnExit()
            writeText(groovy)
        }
    }

    private fun loadTemplate(): String {
        val stream = javaClass.getResourceAsStream(TEMPLATE_RESOURCE)
            ?: error("mirrord: $TEMPLATE_RESOURCE missing from plugin jar")
        return stream.bufferedReader().use { it.readText() }
    }

    /** Groovy set-literal of task names, stripping the `:` project-path prefix. */
    private fun groovyTaskNameFilter(taskNames: List<String>): String {
        val names = taskNames.map { it.removePrefix(":") }
        return names.joinToString(", ") { "'${it.replace("'", "\\'")}'" }
    }

    /** Appends `--init-script <path>` to the Gradle run configuration's script parameters. */
    private fun appendInitScript(configuration: ExternalSystemRunConfiguration, script: File) {
        val scriptPath = script.absolutePath.replace("\\", "/")
        val existing = configuration.settings.scriptParameters ?: ""
        configuration.settings.scriptParameters = "$existing --init-script \"$scriptPath\""
    }
}

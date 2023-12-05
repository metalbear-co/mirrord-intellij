package com.metalbear.mirrord

import com.google.gson.Gson
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.nio.charset.Charset

/**
 * For detecting mirrord config specified in run configuration.
 */
const val CONFIG_ENV_NAME: String = "MIRRORD_CONFIG_FILE"

private const val DEFAULT_CONFIG =
    """{
    "feature": {
        "network": {
            "incoming": "mirror",
            "outgoing": true
        },
        "fs": "read",
        "env": true
    }
}"""

class InvalidConfigException(path: String, reason: String) : MirrordError("failed to process config $path - $reason")

/**
 * Searches mirrord config for target.
 * @throws InvalidConfigException if config cannot be found or parsed.
 */
fun isTargetSet(config: String?): Boolean {
    config ?: return false
    val gson = Gson()

    // `path` will be either a normal string, or the string `"null"` due to `toString`.
    val path = gson.fromJson(config, Map::class.java).let { verified -> verified["path"].toString() }

    // lol
    return path != "null"
}

class InvalidProjectException(project: Project, reason: String) : MirrordError("${project.name} - $reason")

/**
 * Object for interacting with the mirrord config file.
 */
class MirrordConfigAPI(private val service: MirrordProjectService) {
    /**
     * Searches for correct mirrord config path for a run configuration.
     * Displays notifications to the user.
     * @param configFromEnv path to mirrord specified in the configuration.
     */
    fun getConfigPath(configFromEnv: String?): String? {
        service.activeConfig?.let {
            service.notifier.notification(
                "Using mirrord active config",
                NotificationType.INFORMATION
            )
                .withOpenFile(it)
                .withDontShowAgain(MirrordSettingsState.NotificationId.ACTIVE_CONFIG_USED).fire()

            return it.path
        }

        configFromEnv?.let {
            return it
        }

        getDefaultConfig()?.let {
            service.notifier.notification(
                "Using mirrord default config",
                NotificationType.INFORMATION
            )
                .withOpenFile(it)
                .withDontShowAgain(MirrordSettingsState.NotificationId.DEFAULT_CONFIG_USED)
                .fire()
            return it.path
        }

        return null
    }

    /**
     * Finds a parent directory for the `.mirrord` directory. This is a parent directory of the `.idea` directory,
     * the `*.ipr` workspace file or the `*.iml` project file.
     * @throws InvalidProjectException if the directory could not be found.
     */
    private fun getMirrordDirParent(): VirtualFile {
        val knownLocationFile = service.project.projectFile
            ?: service.project.workspaceFile
            ?: throw InvalidProjectException(
                service.project,
                "could not determine parent directory for mirrord files, project must contain a project file or a workspace file"
            )

        val dir = if (knownLocationFile.extension == "xml") {
            knownLocationFile.parent?.parent
        } else {
            knownLocationFile.parent
        }

        return dir
            ?: throw InvalidProjectException(service.project, "could not determine parent directory for mirrord files")
    }

    /**
     * Searches for the `.mirrord` directory in the project.
     * @throws InvalidProjectException if parent directory for `.mirrord` could not be found.
     */
    private fun getMirrordDir(): VirtualFile? {
        return getMirrordDirParent().findChild(".mirrord")?.takeIf { it.isDirectory }
    }

    /**
     * Searches for a default mirrord config in the project.
     * A default project is located in the `.mirrord` directory and its name ends with `mirrord.json`.
     * Candidates are sorted alphabetically and the first one is picked.
     * @throws InvalidProjectException if parent directory for `.mirrord` could not be found.
     */
    fun getDefaultConfig(): VirtualFile? {
        return getMirrordDir()
            ?.children
            ?.filter { it.name.endsWith("mirrord.json") || it.name.endsWith("mirrord.yaml") || it.name.endsWith("mirrord.toml") }
            ?.minByOrNull { it.name }
    }

    /**
     * Creates a default mirrord config in the given project.
     * Config is located under `.mirrord/mirrord.json`.
     * @throws InvalidProjectException if parent directory for `.mirrord` could not be found.
     */
    fun createDefaultConfig(): VirtualFile {
        val mirrordDir = getMirrordDir() ?: getMirrordDirParent().createChildDirectory(this, ".mirrord")

        return mirrordDir.createChildData(this, "mirrord.json")
            .apply { bom = null }
            .apply { charset = Charset.forName("UTF-8") }
            .apply { setBinaryContent(DEFAULT_CONFIG.toByteArray()) }
    }
}

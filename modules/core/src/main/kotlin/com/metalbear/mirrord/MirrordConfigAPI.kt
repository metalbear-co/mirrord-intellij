package com.metalbear.mirrord

import com.google.gson.Gson
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import java.nio.file.InvalidPathException
import java.nio.file.Path

data class Target(val namespace: String?, val path: Any?)

data class ConfigData(val target: Target?)

data class ConfigDataSimple(val target: String?)

/**
 * For detecting mirrord config specified in run configuration.
 */
const val CONFIG_ENV_NAME: String = "MIRRORD_CONFIG_FILE"

private const val DEFAULT_CONFIG =
    """{
    "accept_invalid_certificates": false,
    "feature": {
        "network": {
            "incoming": "mirror",
            "outgoing": true
        },
        "fs": "read",
        "env": true
    }
}
"""

class InvalidConfigException(path: String, reason: String) : Exception() {
    override val message: String = "failed to process mirrord config $path: $reason"
}

/**
 * Searches mirrord config for target.
 * @throws InvalidConfigException if config cannot be found or parsed.
 */
fun isTargetSet(configPath: String): Boolean {
    val file = try {
        VirtualFileManager.getInstance().findFileByNioPath(Path.of(configPath))
            ?: throw InvalidConfigException(configPath, "file not found")
    } catch (_: InvalidPathException) {
        throw InvalidConfigException(configPath, "invalid path")
    }

    val contents = String(file.contentsToByteArray())
    val gson = Gson()

    try {
        val parsed = gson.fromJson(contents, ConfigData::class.java)
        return parsed?.target?.path != null
    } catch (_: Exception) {
    }

    try {
        val parsed = gson.fromJson(contents, ConfigDataSimple::class.java)
        return parsed?.target != null
    } catch (_: Exception) {
        throw InvalidConfigException(configPath, "invalid config")
    }
}

class InvalidProjectException(project: Project, reason: String) : Exception() {
    override val message: String = "${project.name}: $reason"
}

/**
 * Object for interacting with the mirrord config file.
 */
class MirrordConfigAPI(private val service: MirrordProjectService) {
    /**
     * Searches for correct mirrord config path for a run configuration.
     * Displays notifications to the user.
     * @param configFromEnv path to mirrord specified in the configuration.
     * @param createIfMissing whether this method should create a default config as a last resort
     */
    fun getConfigPath(configFromEnv: String?, createIfMissing: Boolean): String? {
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

        if (!createIfMissing) {
            return null
        }

        val config = createDefaultConfig()
        service.notifier.notification(
            "Created a mirrord default config",
            NotificationType.WARNING
        )
            .withOpenFile(config)
            .withDontShowAgain(MirrordSettingsState.NotificationId.DEFAULT_CONFIG_CREATED)
            .fire()
        return config.path
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
        val dir = getMirrordDirParent().findChild(".mirrord") ?: return null

        return if (dir.isDirectory) {
            dir
        } else {
            null
        }
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
            ?.filter { it.name.endsWith("mirrord.json") }
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
            .apply { setBinaryContent(DEFAULT_CONFIG.toByteArray()) }
    }
}

package com.metalbear.mirrord

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.google.gson.Gson
import com.intellij.openapi.vfs.AsyncFileListener
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import java.nio.file.InvalidPathException
import java.nio.file.Path
import com.intellij.openapi.project.ProjectManagerListener
import java.util.concurrent.ConcurrentHashMap

data class Target(val namespace: String?, val path: Any?)

data class ConfigData(val target: Target?)

data class ConfigDataSimple(val target: String?)

/**
 * Object for interacting with the mirrord config file.
 */
object MirrordConfigAPI {
    /**
     * For detecting mirrord config specified in run configuration.
     */
    const val CONFIG_ENV_NAME: String = "MIRRORD_CONFIG_FILE"

    /**
     * Active config files set by the user. Overrides configs specified in run configurations.
     */
    val activeConfigs: ConcurrentHashMap<Project, VirtualFile> = ConcurrentHashMap()

    private const val defaultConfig =
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

    class InvalidConfigException(path: String, reason: String) : Exception("failed to process mirrord config $path: $reason")

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

    /**
     * Searches for correct mirrord config path for a run configuration.
     * @param project project from which the configuration originates.
     * @param configFromEnv path to mirrord specified in the configuration.
     */
    fun getConfigPath(project: Project, configFromEnv: String?): String {
        return activeConfigs[project]?.path ?: configFromEnv ?: getDefaultConfig(project)?.path
        ?: createDefaultConfig(project)!!.path
    }

    class InvalidProjectException(project: Project, reason: String) : Exception("${project.name}: $reason")

    /**
     * Finds a parent directroy for the `.mirrord` directory. This is a parent directory of the `.idea` directory
     * or the `*.ipr` file.
     * @throws InvalidProjectException if the directory could not be found.
     */
    private fun getMirrordDirParent(project: Project): VirtualFile {
        val projectFile = project.projectFile
                ?: throw InvalidProjectException(project, "mirrord cannot be used with the default project")
        val dir = if (projectFile.name == "misc.xml") {
            projectFile.parent?.parent
        } else {
            projectFile.parent
        }

        return dir
                ?: throw InvalidProjectException(project, "could not determine parent directory for mirrord files")
    }

    /**
     * Searches for the `.mirrord` directory in the project.
     * @throws InvalidProjectException if parent directory for `.mirrord` could not be found.
     */
    private fun getMirrordDir(project: Project): VirtualFile? {
        val dir = getMirrordDirParent(project).findChild(".mirrord") ?: return null

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
    fun getDefaultConfig(project: Project): VirtualFile? {
        return getMirrordDir(project)
                ?.children
                ?.filter { it.name.endsWith("mirrord.json") }
                ?.minByOrNull { it.name }
    }

    /**
     * Creates a default mirrord config in the given project.
     * Config is located under `.mirrord/mirrord.json`.
     * @throws InvalidProjectException if parent directory for `.mirrord` could not be found.
     */
    fun createDefaultConfig(project: Project): VirtualFile {
        val mirrordDir = getMirrordDir(project) ?: getMirrordDirParent(project).createChildDirectory(this, ".mirrord")

        return mirrordDir.createChildData(this, "mirrord.json").apply {
            setBinaryContent(defaultConfig.toByteArray())
        }
    }
}

/**
 * Clears active configuration when the file is deleted/moved or the project is closed.
 */
class MirrordConfigWatcher : AsyncFileListener, ProjectManagerListener {
    override fun prepareChange(events: MutableList<out VFileEvent>): AsyncFileListener.ChangeApplier? {
        val affectedProjects = events
                .filter { it is VFileDeleteEvent || it is VFileMoveEvent }
                .mapNotNull { it.file?.url }
                .mapNotNull { deletedUrl ->
                    MirrordConfigAPI.activeConfigs.searchEntries(1) {
                        if (it.value.url == deletedUrl) {
                            it.key
                        } else {
                            null
                        }
                    }
                }

        if (affectedProjects.isEmpty()) {
            return null
        }

        return object : AsyncFileListener.ChangeApplier {
            override fun afterVfsChange() {
                affectedProjects.forEach {
                    MirrordConfigAPI.activeConfigs.remove(it)
                }
            }
        }
    }

    override fun projectClosing(project: Project) {
        MirrordConfigAPI.activeConfigs.remove(project)
    }
}

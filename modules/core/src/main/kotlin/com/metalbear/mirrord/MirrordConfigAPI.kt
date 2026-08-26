package com.metalbear.mirrord

import com.google.gson.Gson
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ProjectRootManager
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
        // Logged at INFO, not DEBUG. Falling through every branch here means the CLI runs with
        // built-in defaults.
        MirrordLogger.logger.info(
            "mirrord.config: resolving — activeConfig=${service.activeConfig?.path ?: "none"} " +
                "fromEnv=${configFromEnv ?: "none"}"
        )

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
                "Using mirrord config from default path",
                NotificationType.INFORMATION
            )
                .withOpenFile(it)
                .withDontShowAgain(MirrordSettingsState.NotificationId.DEFAULT_CONFIG_USED)
                .fire()
            return it.path
        }

        MirrordLogger.logger.warn(
            "mirrord.config: NO CONFIG FOUND — the CLI will run with built-in defaults " +
                "(mirror mode, no http filter, no port mapping). Searched: active config, " +
                "$CONFIG_ENV_NAME, then the project's .mirrord directory."
        )
        return null
    }

    /**
     * Finds the project dir based on some heuristics since service.project.getBasePath don't recommend using it.
     * This is a parent directory of the `.idea` directory, the `*.ipr` workspace file or the `*.iml` project file.
     * @throws InvalidProjectException if the directory could not be found.
     */
    fun getProjectDir(): VirtualFile {
        // `guessProjectDir` is the platform's own answer, and it consults BaseProjectDirectories
        // — the API that is authoritative for a dev-container or remote project, which raw content
        // roots are not.
        //
        // Deriving the root from the project or workspace file holds for a locally opened
        // project. Inside a JetBrains dev container that file lives under the IDE's own
        // configuration directory, so the derived path is ~/.config/JetBrains/<IDE>.
        //
        // Everything anchored here shares that fault: the `.mirrord` lookup, `createDefaultConfig`
        // writing outside version control, and the `$ProjectPath$` macro in MIRRORD_CONFIG_FILE.
        service.project.guessProjectDir()?.let { return it }

        return projectDirFromProjectFile()
    }

    /**
     * The project root as derived from the project or workspace file.
     *
     * Correct only when that file sits inside the project, which is not true in a dev container.
     * Kept as a fallback for projects with no content roots, and used by [getDefaultConfig] so the
     * log can show what each source produced.
     */
    private fun projectDirFromProjectFile(): VirtualFile {
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
        return getProjectDir().findChild(".mirrord")?.takeIf { it.isDirectory }
    }

    /**
     * Searches for a default mirrord config in the project.
     * A default project is located in the `.mirrord` directory and its name ends with `mirrord.json`.
     * Candidates are sorted alphabetically and the first one is picked.
     * @throws InvalidProjectException if parent directory for `.mirrord` could not be found.
     */
    fun getDefaultConfig(): VirtualFile? {
        val contentRoots = runCatching {
            ProjectRootManager.getInstance(service.project).contentRoots.toList()
        }.getOrDefault(emptyList())

        val candidates = buildList {
            service.project.guessProjectDir()?.let { guessed ->
                add(ConfigRootCandidate("guessProjectDir", guessed.path, guessed.findChild(".mirrord")?.takeIf { it.isDirectory }))
            }
            contentRoots.forEachIndexed { index, root ->
                add(ConfigRootCandidate("contentRoot[$index]", root.path, root.findChild(".mirrord")?.takeIf { it.isDirectory }))
            }
            val heuristic = runCatching { projectDirFromProjectFile() }.getOrNull()
            add(ConfigRootCandidate("projectFileHeuristic", heuristic?.path, heuristic?.findChild(".mirrord")?.takeIf { it.isDirectory }))
        }

        candidates.forEach { candidate ->
            MirrordLogger.logger.info(
                "mirrord.config: candidate source=${candidate.source} dir=${candidate.rootPath ?: "none"} " +
                    "mirrordDir=${candidate.mirrordDir?.path ?: "not found"} " +
                    "children=${candidate.mirrordDir?.children?.joinToString(",") { it.name } ?: "-"}"
            )
        }

        return chooseConfigRoot(candidates)
            ?.mirrordDir
            ?.children
            ?.filter { isValidConfigExt(it) }
            ?.minByOrNull { it.name }
    }

    /**
     * Creates a default mirrord config in the given project.
     * Config is located under `.mirrord/mirrord.json`.
     * @throws InvalidProjectException if parent directory for `.mirrord` could not be found.
     */
    fun createDefaultConfig(): VirtualFile {
        val mirrordDir = getMirrordDir() ?: getProjectDir().createChildDirectory(this, ".mirrord")

        return mirrordDir.createChildData(this, "mirrord.json")
            .apply { bom = null }
            .apply { charset = Charset.forName("UTF-8") }
            .apply { setBinaryContent(DEFAULT_CONFIG.toByteArray()) }
    }
    companion object {
        fun isConfigFilePath(file: VirtualFile): Boolean {
            return file.path.contains("mirrord") && isValidConfigExt(file)
        }

        fun isValidConfigExt(file: VirtualFile): Boolean {
            return file.name.endsWith(".json") || file.name.endsWith(".yaml") || file.name.endsWith(".toml")
        }
    }
}

/**
 * One place the `.mirrord` directory might live, and whether it was actually there.
 *
 * [root] and [mirrordDir] are nullable because a candidate source is allowed to produce nothing.
 */
internal data class ConfigRootCandidate<T>(
    val source: String,
    val rootPath: String?,
    val mirrordDir: T?
)

/**
 * Picks the first candidate that actually holds a `.mirrord` directory.
 *
 * Split out from [MirrordConfigAPI.getDefaultConfig] so the ordering rule can be tested without
 * an IDE.
 *
 * The order matters. Deriving the project root from the project or workspace file holds for a
 * locally opened project, but inside a JetBrains dev container that file lives under the IDE's
 * own configuration directory. Content roots come first, and the file-derived heuristic stays a
 * fallback.
 */
internal fun <T> chooseConfigRoot(candidates: List<ConfigRootCandidate<T>>): ConfigRootCandidate<T>? =
    candidates.firstOrNull { it.mirrordDir != null }

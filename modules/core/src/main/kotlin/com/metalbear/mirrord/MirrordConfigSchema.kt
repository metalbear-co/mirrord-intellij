package com.metalbear.mirrord

import com.intellij.execution.RunManager
import com.intellij.execution.RunManagerListener
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.jetbrains.jsonSchema.ide.JsonSchemaService
import com.jetbrains.jsonSchema.remote.JsonFileResolver
import com.metalbear.mirrord.bifrost.MirrordEnvironment
import com.metalbear.mirrord.bifrost.MirrordEnvironments
import com.metalbear.mirrord.bifrost.TargetPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The schema of the latest mirrord release.
 *
 * The plugin uses it only when the mirrord binary is too old to print its own schema.
 */
internal const val GITHUB_CONFIG_SCHEMA_URL = "https://raw.githubusercontent.com/metalbear-co/mirrord/latest/mirrord-schema.json"

/** How long to wait for `mirrord print-schema`. */
private const val PRINT_SCHEMA_TIMEOUT_MILLIS = 10_000L
private const val SCHEMA_RETRY_DELAY_MILLIS = 1_000L
private const val MAX_SCHEMA_RETRIES = 2

/** Where the schema of the mirrord config file comes from. */
internal sealed interface MirrordConfigSchemaSource {
    /** The schema that the mirrord binary printed with `mirrord print-schema`. */
    data class Binary(val schema: String) : MirrordConfigSchemaSource {
        /** The in-memory file that the IDE reads the schema from. */
        val file: VirtualFile = LightVirtualFile("mirrord-schema.json", schema)
    }

    /** The binary is too old to print its schema, so the plugin uses [GITHUB_CONFIG_SCHEMA_URL]. */
    data object GitHub : MirrordConfigSchemaSource

    /**
     * There is no schema. This is the case before the first load, when there is no mirrord binary,
     * and while the plugin waits to learn the latest mirrord version.
     */
    data object None : MirrordConfigSchemaSource

    /** The binary could not print its schema because execution failed or timed out. */
    data object Failed : MirrordConfigSchemaSource
}

/**
 * Asks the mirrord binary at [binary] for the schema of its config file.
 *
 * Binaries older than 3.257.0 do not have the `print-schema` command, so it fails. For them, this
 * returns [MirrordConfigSchemaSource.GitHub]. When the binary cannot run, for example when it does
 * not finish in time, this returns [MirrordConfigSchemaSource.Failed] so the service can retry.
 */
internal fun configSchemaOf(binary: TargetPath, environment: MirrordEnvironment): MirrordConfigSchemaSource {
    val output = try {
        environment.probe(binary, listOf("print-schema"), PRINT_SCHEMA_TIMEOUT_MILLIS)
    } catch (e: ProcessCanceledException) {
        throw e
    } catch (e: Exception) {
        MirrordLogger.logger.warn("mirrord config schema: `$binary print-schema` did not run, so there is no schema", e)
        return MirrordConfigSchemaSource.Failed
    }

    // Some environments report a timeout in the output, and do not throw.
    if (output.isTimeout || output.isCancelled) {
        MirrordLogger.logger.warn("mirrord config schema: `$binary print-schema` did not finish, so there is no schema")
        return MirrordConfigSchemaSource.Failed
    }

    val stdout = output.stdout
    if (output.exitCode != 0 || stdout.isBlank()) {
        MirrordLogger.logger.info(
            "mirrord config schema: `$binary print-schema` failed with exit code ${output.exitCode}, " +
                "using $GITHUB_CONFIG_SCHEMA_URL. stderr: ${output.stderr.trim().take(200)}"
        )
        return MirrordConfigSchemaSource.GitHub
    }

    MirrordLogger.logger.info("mirrord config schema: taken from $binary")
    return MirrordConfigSchemaSource.Binary(stdout)
}

/**
 * Holds the schema of the mirrord config file for one project.
 *
 * The editor follows the selected run configuration's target. With no selection, it uses the
 * project's environment. The binary is asked for the schema in the
 * background: first when a mirrord config file needs the schema, and again each time that the
 * binary may have changed ([MirrordBinaryListener]).
 *
 * While automatic updates check the latest version, an installed binary can still supply its
 * schema. This keeps validation working if the version check fails. GitHub fallback waits until
 * the binary selection is settled.
 *
 * When there is no binary, there is no schema. The plugin does not fetch the schema from GitHub
 * in this case, because some security tools block or warn about requests to GitHub. The schema
 * appears when the binary appears, for example when its download ends.
 */
@Service(Service.Level.PROJECT)
@OptIn(ExperimentalCoroutinesApi::class)
class MirrordConfigSchema(private val project: Project, scope: CoroutineScope) {
    /** The source of the schema that the IDE uses now. */
    @Volatile
    internal var source: MirrordConfigSchemaSource = MirrordConfigSchemaSource.None
        private set

    /** True after the first load ended. */
    @Volatile
    @get:TestOnly
    var isLoaded = false
        private set

    /** True after a mirrord config file needed the schema. Before that, nothing is loaded. */
    private val needed = AtomicBoolean(false)

    /** Asks for a new load. Requests that come while a load runs become one request. */
    private val loadRequests = Channel<Long>(Channel.CONFLATED)

    /** Guarded by [loadRequests], so a new request cannot race with storing a result. */
    private var latestRequest = 0L

    /** An empty schema, see [schemaFile]. */
    private val placeholder: VirtualFile = LightVirtualFile("mirrord-schema.json", "{}")

    init {
        ApplicationManager.getApplication().messageBus.connect(scope).subscribe(
            MirrordBinaryListener.TOPIC,
            MirrordBinaryListener {
                if (needed.get()) {
                    requestLoad()
                }
            }
        )

        project.messageBus.connect(scope).subscribe(
            RunManagerListener.TOPIC,
            object : RunManagerListener {
                override fun runConfigurationSelected(settings: RunnerAndConfigurationSettings?) {
                    if (needed.get()) requestLoad()
                }

                override fun runConfigurationChanged(settings: RunnerAndConfigurationSettings) {
                    if (needed.get() && RunManager.getInstance(project).selectedConfiguration == settings) {
                        requestLoad()
                    }
                }
            }
        )

        scope.launch(Dispatchers.IO) {
            var request = loadRequests.receive()
            var retries = 0
            while (true) {
                val loaded = findSource()
                // A project can close while the blocking lookup runs.
                currentCoroutineContext().ensureActive()

                // Keep the old file when the text is equal, so the IDE does not parse it again.
                val changed = loaded != source
                val current = synchronized(loadRequests) {
                    // Finish each lookup, but discard its result if a newer request arrived.
                    if (request != latestRequest) return@synchronized false

                    if (changed) source = loaded
                    isLoaded = true
                    true
                }
                if (current && changed) {
                    JsonSchemaService.Impl.get(project).reset()
                }

                // A newer request replaces a delayed retry immediately.
                val next = if (current && loaded == MirrordConfigSchemaSource.Failed && retries < MAX_SCHEMA_RETRIES) {
                    select<Long?> {
                        loadRequests.onReceive { it }
                        onTimeout(SCHEMA_RETRY_DELAY_MILLIS * (retries + 1)) { null }
                    }
                } else {
                    loadRequests.receive()
                }
                if (next == null) {
                    retries += 1
                } else {
                    request = next
                    retries = 0
                }
            }
        }
    }

    /**
     * The file that the IDE reads the schema from.
     *
     * When there is no schema, this is an empty schema, and not null, for two reasons:
     * - The IDE drops a provider whose schema file is null. Then it never asks the provider if a
     *   file is a mirrord config file, so [markNeeded] would never be called.
     * - With no schema from this provider, the IDE takes the schema from the SchemaStore catalog,
     *   which fetches it from GitHub.
     */
    internal fun schemaFile(): VirtualFile = when (val current = source) {
        is MirrordConfigSchemaSource.Binary -> current.file
        MirrordConfigSchemaSource.GitHub -> JsonFileResolver.urlToFile(GITHUB_CONFIG_SCHEMA_URL) ?: placeholder
        MirrordConfigSchemaSource.None, MirrordConfigSchemaSource.Failed -> placeholder
    }

    /**
     * Tells that a mirrord config file needs the schema. The first call starts the first load.
     * Later loads start when the binary may have changed.
     */
    fun markNeeded() {
        if (!needed.get() && needed.compareAndSet(false, true)) {
            requestLoad()
        }
    }

    private fun requestLoad() {
        synchronized(loadRequests) {
            latestRequest += 1
            loadRequests.trySend(latestRequest)
        }
    }

    private fun findSource(): MirrordConfigSchemaSource = try {
        val profile = RunManager.getInstance(project).selectedConfiguration?.configuration
        val environment = if (profile == null) {
            MirrordEnvironments.forProject(project)
        } else {
            MirrordEnvironments.forRunProfile(project, profile)
        }
        val selection = service<MirrordBinaryManager>().findBinary(environment)
        val binary = selection.command
        val found = if (binary == null) {
            MirrordLogger.logger.info("mirrord config schema: no mirrord binary, so there is no schema")
            MirrordConfigSchemaSource.None
        } else {
            configSchemaOf(binary, environment)
        }

        if (found == MirrordConfigSchemaSource.GitHub && !selection.isSettled) {
            MirrordLogger.logger.info("mirrord config schema: waiting for the binary selection, so there is no schema yet")
            MirrordConfigSchemaSource.None
        } else {
            found
        }
    } catch (e: ProcessCanceledException) {
        throw e
    } catch (e: Exception) {
        // An exception here would end the load loop, and then the schema would never load again.
        MirrordLogger.logger.warn("mirrord config schema: could not find the mirrord binary, so there is no schema", e)
        MirrordConfigSchemaSource.None
    }
}

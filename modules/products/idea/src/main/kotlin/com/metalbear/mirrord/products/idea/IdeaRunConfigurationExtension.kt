@file:Suppress("UnstableApiUsage")

package com.metalbear.mirrord.products.idea

import com.intellij.debugger.impl.GenericDebuggerRunnerSettings
import com.intellij.execution.CommonProgramRunConfigurationParameters
import com.intellij.execution.RunConfigurationExtension
import com.intellij.execution.configurations.JavaParameters
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.service
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemRunConfiguration
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.metalbear.mirrord.MirrordBinaryManager
import com.metalbear.mirrord.MirrordLogger
import com.metalbear.mirrord.MirrordPitm
import com.metalbear.mirrord.MirrordProjectService
import com.metalbear.mirrord.bifrost.MirrordEnvironments
import com.metalbear.mirrord.bifrost.MirrordLaunchContext
import com.metalbear.mirrord.isWinNative
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Whether the IDE is launching a JVM debug session (vs. plain run). */
internal val RunnerSettings?.isJvmDebug: Boolean
    get() = this is GenericDebuggerRunnerSettings

/**
 * Whether this run configuration is handled by IntelliJ's external build-system
 * integration (Gradle, Maven, SBT, Bazel-tasks). Contract-enabled so the caller
 * gets a smart-cast to [ExternalSystemRunConfiguration] in the `true` branch.
 */
@OptIn(kotlin.contracts.ExperimentalContracts::class)
internal fun RunConfigurationBase<*>.isExternalBuildSystem(): Boolean {
    kotlin.contracts.contract {
        returns(true) implies (this@isExternalBuildSystem is ExternalSystemRunConfiguration)
    }
    return this is ExternalSystemRunConfiguration
}

/**
 * For overriding the `isApplicableFor` check on run configurations.
 * Set MIRRORD_FORCE_RUN=true to ensure we consider the run configuration able to run with mirrord.
 * NOTE: mirrord must _still be enabled_ to run on the run configuration.
 */
const val FORCE_RUN_ENV_NAME: String = "MIRRORD_FORCE_RUN"

private const val GRADLE_RUN_CONFIGURATION = "org.jetbrains.plugins.gradle.service.execution.GradleRunConfiguration"

private fun RunConfigurationBase<*>.isGradleRunConfiguration(): Boolean =
    javaClass.name == GRADLE_RUN_CONFIGURATION

/**
 * Exact task names that never fork a user JVM (packaging / clean / docs / housekeeping).
 */
private val GRADLE_BUILD_ONLY_EXACT = setOf(
    "clean",
    "jar", "war", "bootJar", "bootWar", "shadowJar", "distTar", "distZip",
    "javadoc",
    "wrapper", "init",
    "classes", "testClasses"
)

/**
 * Prefix matches for build-only tasks. Covers multiplatform / Android / custom
 * source-set variants: `compileJava`, `compileKotlinJvm`, `compileDebugJavaWithJavac`,
 * `compileIntegrationTestJava`, `processResources`, `processTestResources`, etc.
 */
private val GRADLE_BUILD_ONLY_PREFIXES = listOf(
    "compile",
    "process" // processResources, process*Resources
)

private fun isGradleBuildOnlyTask(task: String): Boolean {
    val bare = task.removePrefix(":").substringAfterLast(':')
    return GRADLE_BUILD_ONLY_EXACT.any { bare.equals(it, ignoreCase = true) } ||
        GRADLE_BUILD_ONLY_PREFIXES.any { bare.startsWith(it, ignoreCase = true) }
}

internal fun isIdeaConfigurationApplicableForMirrord(configuration: RunConfigurationBase<*>): Boolean {
    val skipTomcat = configuration.name.startsWith("Build ") || configuration.name.startsWith("Tomcat")

    val gradleTaskNames = (configuration as? ExternalSystemRunConfiguration)?.settings?.taskNames ?: emptyList()
    val skipGradleBuild = configuration.isGradleRunConfiguration() &&
        gradleTaskNames.isNotEmpty() &&
        gradleTaskNames.all(::isGradleBuildOnlyTask)

    val forceRunMirrord = getForceRunMirrord(configuration)

    return forceRunMirrord || !(skipTomcat || skipGradleBuild)
}

internal fun getIdeaConfigurationEnv(configuration: RunConfigurationBase<*>): Map<String, String> {
    return when (configuration) {
        is ExternalSystemRunConfiguration -> configuration.settings.env
        is CommonProgramRunConfigurationParameters -> configuration.envs
        else -> emptyMap()
    }
}

private fun getForceRunMirrord(configuration: RunConfigurationBase<*>): Boolean {
    return if (configuration is ExternalSystemRunConfiguration) {
        configuration.settings.env[FORCE_RUN_ENV_NAME].toBoolean()
    } else {
        false
    }
}

class IdeaRunConfigurationExtension : RunConfigurationExtension() {
    /**
     * mirrord env set in ExternalRunConfigurations. Used for cleanup the configuration after the execution has ended.
     */
    private val runningProcessEnvs = ConcurrentHashMap<RunConfigurationBase<*>, Map<String, String>>()
    private val runningProcessScriptParams = ConcurrentHashMap<RunConfigurationBase<*>, String>()

    override fun isApplicableFor(configuration: RunConfigurationBase<*>): Boolean {
        val applicable = isIdeaConfigurationApplicableForMirrord(configuration)

        if (!applicable) {
            MirrordLogger.logger.info("Configuration name %s ignored".format(configuration.name))
        }

        return applicable
    }

    override fun isEnabledFor(
        applicableConfiguration: RunConfigurationBase<*>,
        runnerSettings: RunnerSettings?
    ): Boolean {
        return true
    }

    override fun <T : RunConfigurationBase<*>> updateJavaParameters(
        configuration: T,
        params: JavaParameters,
        runnerSettings: RunnerSettings?
    ) {
        val configSummary = "cfg=`${configuration.name}` class=${configuration.javaClass.simpleName} " +
            "runnerSettings=${runnerSettings?.javaClass?.simpleName ?: "null"} " +
            "isExternal=${configuration.isExternalBuildSystem()}"
        MirrordLogger.logger.info("updateJavaParameters: ENTER $configSummary")

        val executionInfo = IdeaMirrordPreparationStore.consume(configuration) ?: run {
            // Main mirrord initialization is prepared by a before-run task to avoid blocking here under read lock.
            MirrordLogger.logger.info("updateJavaParameters: no prepared executionInfo for `${configuration.name}`, skipping (check IdeaExecutionListener/BeforeRunTask ran)")
            return
        }

        val isDebug = runnerSettings.isJvmDebug
        val mirrordEnv = executionInfo.environment + ("MIRRORD_DETECT_DEBUGGER_PORT" to "javaagent")
        val envToUnset = executionInfo.envToUnset
        MirrordLogger.logger.info(
            "updateJavaParameters: executionInfo consumed, mirrordEnv size=${mirrordEnv.size}, envToUnset size=${envToUnset?.size ?: 0}"
        )

        // Gating only: which injection mechanism this target needs.
        //
        // Deliberately reads the *stored* platform rather than resolving an environment here.
        // This method runs under a read lock, and resolving would mean blocking on a possibly
        // cold dev container from inside it — a good way to deadlock the IDE.
        val environment = MirrordEnvironments.resolve(MirrordLaunchContext(configuration.project))
        val winNative = isWinNative(environment.platform())
        MirrordLogger.logger.info(
            "updateJavaParameters: platform gating winNative=$winNative env=${environment.name} isDebug=$isDebug"
        )

        // On Windows native, try to wrap the JDK with mirrord pitm. When that
        // succeeds, the mirrord env vars are ferried to the child via
        // MIRRORD_CHILD_ENV only — they must NOT be set on params.env directly,
        // or the mirrord.exe wrapper itself would inherit them.
        //
        // ExternalSystemRunConfiguration (Gradle/Maven) is excluded because
        // params.jdk is null — Gradle manages its own JDK. Windows-native Gradle
        // (both Run and Debug) is handled by the init-script pitm wrap below.
        val envSizeBefore = params.env.size
        val usesWinGradlePitm = winNative && configuration.isGradleRunConfiguration()
        val pitmWrapped = if (winNative && !configuration.isExternalBuildSystem()) {
            MirrordLogger.logger.info("updateJavaParameters: branch=NON_GRADLE_WIN, attempting wrapJdkWithPitm")
            wrapJdkWithPitm(configuration.project, params, mirrordEnv, envToUnset)
        } else {
            false
        }

        if (!pitmWrapped && !usesWinGradlePitm) {
            params.env = params.env + mirrordEnv - envToUnset.orEmpty().toSet()
            MirrordLogger.logger.info(
                "updateJavaParameters: mirrord env set on params.env directly (pitm NOT engaged), " +
                    "params.env size $envSizeBefore → ${params.env.size}"
            )
        } else if (usesWinGradlePitm) {
            MirrordLogger.logger.info(
                "updateJavaParameters: deferring mirrord env to the Windows Gradle child payload, " +
                    "params.env remains ${params.env.size} vars"
            )
        } else {
            MirrordLogger.logger.info(
                "updateJavaParameters: pitm JDK wrap succeeded, params.env size $envSizeBefore → ${params.env.size}"
            )
        }

        // Gradle support (and external system configuration)
        if (configuration.isExternalBuildSystem()) {
            val externalSystemBranch = when {
                usesWinGradlePitm && !isDebug -> "GRADLE_WIN_RUN"
                usesWinGradlePitm && isDebug -> "GRADLE_WIN_DEBUG"
                else -> "GRADLE_PLAIN_ENV"
            }
            MirrordLogger.logger.info(
                "updateJavaParameters: ExternalSystem branch=$externalSystemBranch " +
                    "taskNames=${configuration.settings.taskNames}"
            )

            runningProcessEnvs[configuration] = configuration.settings.env.toMap()
            configuration.settings.scriptParameters?.let {
                runningProcessScriptParams[configuration] = it
            }
            MirrordLogger.logger.debug(
                "updateJavaParameters: snapshotted settings.env size=${configuration.settings.env.size}, " +
                    "scriptParametersPresent=${!configuration.settings.scriptParameters.isNullOrBlank()}"
            )

            when {
                usesWinGradlePitm -> {
                    // Windows native, Run AND Debug: inject a Gradle init script that
                    // relaunches matched JavaExec tasks under `mirrord pitm`, ferrying
                    // env vars via MIRRORD_CHILD_ENV so they only reach the child.
                    //
                    // IntelliJ's `ijJvmDebugger` init script adds the dispatched JDWP
                    // argument to the application JavaExec task. The mirrord init script
                    // preserves that late argument and hands its port to the layer through
                    // MIRRORD_IGNORE_DEBUGGER_PORTS, leaving the debugger's loopback
                    // connection unmanaged.
                    MirrordWinGradleInjector.wrap(configuration, mirrordEnv, envToUnset, isDebug)
                }
                else -> {
                    // Non-Windows: set env vars directly on the config.
                    val env = configuration.settings.env + mirrordEnv - envToUnset.orEmpty().toSet()
                    configuration.settings.env = env
                    MirrordLogger.logger.info(
                        "updateJavaParameters: settings.env updated directly, now ${configuration.settings.env.size} vars"
                    )
                }
            }
        }
        MirrordLogger.logger.info("updateJavaParameters: EXIT $configSummary")
    }

    /**
     * Replaces `params.jdk` with a fake JDK whose `bin/java.exe` is actually
     * `mirrord.exe`. This is necessary because IntelliJ builds the java command
     * line as `<jdk.homePath>/bin/java.exe <args>` — there is no other way to
     * override the executable for a `JavaParameters`-based launch.
     *
     * When IntelliJ invokes this fake `java.exe`, mirrord's `run_as_java_launcher`
     * detects `argv[0]` is `java.exe` and enters pitm mode using:
     * (https://github.com/metalbear-co/mirrord/blob/main/mirrord/cli/src/pitm.rs)
     *
     *   - [MirrordPitmJdk.REAL_JAVA_ENV] — path to the real `java.exe`; pitm
     *     spawns it suspended and injects the layer before resuming.
     *   - [MirrordPitm.CHILD_ENV_VAR] — base64-JSON `{set, unset}` payload
     *     that pitm decodes to build the child environment.
     *
     * Returns `true` on success. On failure the caller falls back to setting
     * [mirrordEnvVars] directly on `params.env`.
     */
    private fun wrapJdkWithPitm(
        project: Project,
        params: JavaParameters,
        mirrordEnvVars: Map<String, String>,
        envToUnset: List<String>?
    ): Boolean {
        MirrordLogger.logger.info(
            "wrapJdkWithPitm: ENTER jdk=${params.jdk?.name ?: "null"} jdkHome=${params.jdk?.homePath ?: "null"} " +
                "mirrordEnvVars=${mirrordEnvVars.size} envToUnset=${envToUnset?.size ?: 0}"
        )

        val realJdk = params.jdk
        if (realJdk == null) {
            MirrordLogger.logger.error("wrapJdkWithPitm: no JDK on run config, aborting")
            project.service<MirrordProjectService>().notifier.notifySimple(
                "mirrord: no JDK on run config; pitm wrap failed",
                NotificationType.ERROR
            )
            return false
        }
        val realHome = realJdk.homePath
        if (realHome == null) {
            MirrordLogger.logger.error("wrapJdkWithPitm: JDK `${realJdk.name}` has no homePath, aborting")
            project.service<MirrordProjectService>().notifier.notifySimple(
                "mirrord: JDK `${realJdk.name}` has no homePath; pitm wrap failed",
                NotificationType.ERROR
            )
            return false
        }

        val mirrordExe = try {
            File(service<MirrordBinaryManager>().getBinary("idea", MirrordEnvironments.resolve(MirrordLaunchContext(project)), project).value)
        } catch (e: Exception) {
            MirrordLogger.logger.warn("wrapJdkWithPitm: failed to resolve mirrord binary: ${e.message}", e)
            project.service<MirrordProjectService>().notifier.notifySimple(
                "mirrord: could not resolve mirrord binary for pitm wrap (${e.message}); layer will not load",
                NotificationType.ERROR
            )
            return false
        }
        MirrordLogger.logger.debug(
            "wrapJdkWithPitm: mirrordExe=${mirrordExe.absolutePath} exists=${mirrordExe.isFile} size=${if (mirrordExe.isFile) mirrordExe.length() else -1}"
        )

        val wrapped = MirrordPitmJdk.wrap(realJdk, mirrordExe) ?: run {
            MirrordLogger.logger.warn("wrapJdkWithPitm: MirrordPitmJdk.wrap returned null — see MirrordPitmJdk logs above")
            project.service<MirrordProjectService>().notifier.notifySimple(
                "mirrord: fake JDK preparation failed; pitm wrap skipped",
                NotificationType.WARNING
            )
            return false
        }

        val childEnvPayload = MirrordPitm.encodeChildEnv(mirrordEnvVars, envToUnset)

        val envSizeBefore = params.env.size
        params.jdk = wrapped
        params.env = params.env + mapOf(
            MirrordPitmJdk.REAL_JAVA_ENV to "$realHome/bin/java.exe",
            MirrordPitm.CHILD_ENV_VAR to childEnvPayload
        )
        // Guardrail: if any raw mirrord env vars leaked onto params.env, the
        // mirrord.exe wrapper itself would inherit them. Detect and loudly warn.
        val leaked = params.env.keys.intersect(mirrordEnvVars.keys)
        if (leaked.isNotEmpty()) {
            MirrordLogger.logger.warn(
                "wrapJdkWithPitm: LEAK — mirrord env vars present on params.env after pitm wrap: $leaked. " +
                    "These should only live inside MIRRORD_CHILD_ENV."
            )
        }
        MirrordLogger.logger.info(
            "wrapJdkWithPitm: SUCCESS realJava=$realHome/bin/java.exe " +
                "childEnvPayload.len=${childEnvPayload.length} " +
                "params.env $envSizeBefore → ${params.env.size} (+REAL_JAVA, +CHILD_ENV)"
        )
        return true
    }

    /**
     * Remove mirrord env leftovers from the external system configurations.
     */
    override fun attachToProcess(
        configuration: RunConfigurationBase<*>,
        handler: ProcessHandler,
        runnerSettings: RunnerSettings?
    ) {
        if (configuration.isExternalBuildSystem()) {
            val envsToRestore = runningProcessEnvs.remove(configuration) ?: return
            val scriptParamsToRestore = runningProcessScriptParams.remove(configuration)

            handler.addProcessListener(object : ProcessListener {
                override fun processTerminated(event: ProcessEvent) {
                    configuration.settings.env.apply {
                        clear()
                        putAll(envsToRestore)
                    }
                    configuration.settings.scriptParameters = scriptParamsToRestore // null if key wasn't present
                }

                override fun startNotified(event: ProcessEvent) {}

                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {}
            })
        }
    }
}

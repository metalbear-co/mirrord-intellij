package com.metalbear.mirrord.products.bazel


import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.util.SystemInfo
import com.metalbear.mirrord.MirrordExecution
import com.metalbear.mirrord.MirrordLogger
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.toImmutableMap
import kotlin.reflect.KClass

class BazelBinaryProvider241(var env : ExecutionEnvironment) : BazelBinaryProvider {

    class BinaryExecutionPlan241(val state: Any, val blazeBinary : String, val binaryToPatch: String?) : BinaryExecutionPlan {

        override fun getOriginalEnv(): ImmutableMap<String, String> {
            val env = getPropertyByName(state, "userEnvVarsState.data.envs") as Map<String, String>
            return env.toImmutableMap()
        }

        override fun addToEnv(map: Map<String, String>) {
            val env = getPropertyByName(state, "userEnvVarsState")
            callFunction(env!!, "setEnvVars", map)
        }

        override fun getBinaryToPatch(): String? {
            return binaryToPatch
        }

        override fun checkExecution(executionInfo: MirrordExecution): String {
            val originalBinary = getPropertyByName(state, "blazeBinaryState.blazeBinary") as String
            if (SystemInfo.isMac) {
                executionInfo.patchedPath ?: let {
                    MirrordLogger.logger.debug("[${this.javaClass.name}] processStartScheduled: patchedPath is not null: $it, meaning original was SIP")
                    setPropertyFromName(state, "blazeBinaryState.blazeBinary", it )
                } ?: run {
                    MirrordLogger.logger.debug("[${this.javaClass.name}] processStartScheduled: isMac, but not patching SIP (no patched path returned by the CLI).")
                }
            }
            return originalBinary
        }

        override fun restoreConfig(savedConfigData: SavedConfigData) {
            MirrordLogger.logger.debug("[${this.javaClass.name}] restoreConfig: found ${savedConfigData.envVars.size} saved original variables")
            val userEnvVarState = getPropertyByName(state, "userEnvVarsState")
            callFunction(userEnvVarState!!, "setEnvVars", savedConfigData.envVars)

            if (SystemInfo.isMac) {
                MirrordLogger.logger.debug("[${this.javaClass.name}] restoreConfig: found saved original Bazel path ${blazeBinary}")
                setPropertyFromName(state, "blazeBinaryState.blazeBinary", blazeBinary)
            }
        }

    }

    companion object Factory {
        fun fromExecutionEnv(env : ExecutionEnvironment) : BazelBinaryProvider241 {
            return BazelBinaryProvider241(env)
        }
    }

    override fun provideTargetBinaryExecPlan(executorId: String) : BinaryExecutionPlan? {

        //////////////////////////////////////////////////////
        // java.lang.NoSuchMethodError: 'com.google.idea.blaze.base.bazel.BuildSystem$BuildInvoker
        // com.google.idea.blaze.base.bazel.BuildSystem.getBuildInvoker(com.intellij.openapi.project.Project, com.google.idea.blaze.base.scope.BlazeContext)'
        //////////////////////////////////////////////////////

        val state = castFromClassName(this.env.runProfile, "BlazeCommandRunConfiguration") ?:
            let {
                val uncastedState = getPropertyByName(it, "handler.state")
                castFromClassName(uncastedState, "com.google.idea.blaze.base.run.state.BlazeCommandRunConfigurationCommonState")
            }
            .run {
                MirrordLogger.logger.debug("[${javaClass.name}] processStartScheduled: Bazel not detected")
                execListener.processStartScheduled(executorId, env)
                throw BuildExecPlanError("Bazel not detected")
            }

        ///////////////////////////////////////////////////////
        // runProfile.handler.state as? BlazeCommandRunConfigurationCommonState
        ///////////////////////////////////////////////////////

        val binaryToPatch = if (SystemInfo.isMac) {

            val blazeBinary = getPropertyByName(state, "blazeBinaryState.blazeBinary")?.let{
                MirrordLogger.logger.debug("[${this.javaClass.name}] processStartScheduled: found Bazel binary path in the config: $it")
                it
            } ?: run {
                // Bazel binary path may be missing from the config.
                // This is the logic that Bazel plugin uses to find global Bazel binary.
                val blaze = Class.forName("com.google.idea.blaze.base.settings.Blaze")
                    .getMethod("getBuildInvoker", ExecutionEnvironment::class.java).invoke(null, env)


                val global = Blaze.getBuildSystemProvider(env.project).buildSystem
                    .getBuildInvoker(env.project, BlazeContext.create()).binaryPath
                MirrordLogger.logger.debug("[${this.javaClass.name} processStartScheduled: found global Bazel binary path: $global")
                global
            }

        } else {
            null
        }

        return BinaryExecutionPlan241(state, , binaryToPatch)
    }

    override fun getBinaryExecPlanClass(): KClass<out BinaryExecutionPlan> {
        return BinaryExecutionPlan241::class
    }

}
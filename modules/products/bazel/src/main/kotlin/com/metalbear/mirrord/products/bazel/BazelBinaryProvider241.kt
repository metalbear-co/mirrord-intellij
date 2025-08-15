package com.metalbear.mirrord.products.bazel


import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.util.SystemInfo
import com.metalbear.mirrord.MirrordExecution
import com.metalbear.mirrord.MirrordLogger
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.toImmutableMap
import kotlin.reflect.KClass

class BazelBinaryProvider241(var env : ExecutionEnvironment) : BazelBinaryProvider {

    class BinaryExecutionPlan241(val state: Any, val binaryToPatch: String?) : BinaryExecutionPlan {

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
                    setPropertyByName(state, "blazeBinaryState.blazeBinary", it )
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
                val originalBlazeBinary = savedConfigData.bazelPath
                MirrordLogger.logger.debug("[${this.javaClass.name}] restoreConfig: found saved original Bazel path ${originalBlazeBinary}")
                setPropertyByName(state, "blazeBinaryState.blazeBinary", originalBlazeBinary)
            }
        }

    }

    companion object Factory {
        fun fromExecutionEnv(env : ExecutionEnvironment) : BazelBinaryProvider241 {
            return BazelBinaryProvider241(env)
        }
    }

    override fun provideTargetBinaryExecPlan(executorId: String) : BinaryExecutionPlan? {

        val state = castFromClassName(this.env.runProfile, "com.google.idea.blaze.base.run.BlazeCommandRunConfiguration") ?:
            let {
                val uncastedState = getPropertyByName(it, "handler.state")
                castFromClassName(uncastedState, "com.google.idea.blaze.base.run.state.BlazeCommandRunConfigurationCommonState")
            }
            .run {
                MirrordLogger.logger.debug("[${javaClass.name}] processStartScheduled: Bazel not detected")
                throw BuildExecPlanError("Bazel not detected")
            }

        val binaryToPatch = if (SystemInfo.isMac) {
            getPropertyByName(state, "blazeBinaryState.blazeBinary") ?. let {
                MirrordLogger.logger.debug("[${this.javaClass.name}] processStartScheduled: found Bazel binary path in the config: $it")
                it as String
            } ?: run {
                val blaze = Class.forName("com.google.idea.blaze.base.settings.Blaze")
                val buildSystemProvider = callFunction(blaze, "getBuildSystemProvider", env.project)!!
                val buildSystem = getPropertyByName(buildSystemProvider, "buildSystem")!!

                val blazeContextClass = Class.forName("com.google.idea.blaze.base.scope.BlazeContext")
                val blazeContext = callFunction(blazeContextClass, "create" )
                val buildInvoker =  callFunction(buildSystem, "getBuildInvoker", env.project, blazeContext)!!
                getPropertyByName(buildInvoker, "binaryPath") as String?
            }
        } else {
            null
        }

        return BinaryExecutionPlan241(state,binaryToPatch)

    }

    override fun getBinaryExecPlanClass(): KClass<out BinaryExecutionPlan> {
        return BinaryExecutionPlan241::class
    }

}
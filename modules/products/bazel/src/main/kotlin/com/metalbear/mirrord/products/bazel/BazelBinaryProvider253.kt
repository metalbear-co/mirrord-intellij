package com.metalbear.mirrord.products.bazel

import com.intellij.execution.configuration.EnvironmentVariablesData
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.util.SystemInfo
import com.metalbear.mirrord.MirrordExecution
import com.metalbear.mirrord.MirrordLogger
import com.metalbear.mirrord.products.bazel.BazelBinaryProvider241.BinaryExecutionPlan241
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.toImmutableMap
import kotlin.reflect.KClass

class BazelBinaryProvider253(var env: ExecutionEnvironment) : BazelBinaryProvider {

    class BinaryExecutionPlan253(private val plan: BinaryExecutionPlan241, private val originalEnv: Map<String, String>) :
        BinaryExecutionPlan {

        override fun getOriginalEnv(): ImmutableMap<String, String> {
            return originalEnv.toImmutableMap()
        }

        override fun addToEnv(map: Map<String, String>) {
            val oldEnv = ReflectUtils.getPropertyByName(plan.state, "handler.state.envVars.data.myEnvs") as Map<String, String>
            val newEnvMap = HashMap(oldEnv)
            newEnvMap.putAll(map)
            val oldEnvFile = ReflectUtils.getPropertyByName(plan.state, "handler.state.envVars.data.myEnvironmentFile") as String?
            val oldPassParentEnvs = ReflectUtils.getPropertyByName(plan.state, "handler.state.envVars.data.myPassParentEnvs") as Boolean
            val newEnv = EnvironmentVariablesData.create(newEnvMap, oldPassParentEnvs, oldEnvFile)
            ReflectUtils.setPropertyByName(plan.state, "handler.state.envVars.data.myEnvs", newEnv)
        }

        override fun getBinaryToPatch(): String? {
            return plan.getBinaryToPatch()
        }

        override fun checkExecution(executionInfo: MirrordExecution): String {
            val originalBinary = ReflectUtils.getPropertyByName(plan.state, "handler.state.blazeBinary")!! as String
            if (SystemInfo.isMac) {
                executionInfo.patchedPath ?: let {
                    MirrordLogger.logger.debug("[${this.javaClass.name}] processStartScheduled: patchedPath is not null: $it, meaning original was SIP")
                    ReflectUtils.setPropertyByName(plan.state, "handler.state.blazeBinary", it)
                } ?: run {
                    MirrordLogger.logger.debug("[${this.javaClass.name}] processStartScheduled: isMac, but not patching SIP (no patched path returned by the CLI).")
                }
            }
            return originalBinary
        }

        override fun restoreConfig(savedConfigData: SavedConfigData) {
            MirrordLogger.logger.debug("[${this.javaClass.name}] restoreConfig: found ${savedConfigData.envVars.size} saved original variables")
            val oldEnvFile = ReflectUtils.getPropertyByName(plan.state, "handler.state.envVars.data.myEnvironmentFile") as String
            val oldPassParentEnvs = ReflectUtils.getPropertyByName(plan.state, "handler.state.envVars.data.myPassParentEnvs") as Boolean
            val newEnv = EnvironmentVariablesData.create(savedConfigData.envVars, oldPassParentEnvs, oldEnvFile)
            ReflectUtils.setPropertyByName(plan.state, "handler.state.envVars.data.myEnvs", newEnv)
            if (SystemInfo.isMac) {
                val originalBlazeBinary = savedConfigData.bazelPath
                MirrordLogger.logger.debug("[${this.javaClass.name}] restoreConfig: found saved original Bazel path $originalBlazeBinary")
                ReflectUtils.setPropertyByName(plan.state, "handler.state.blazeBinary", originalBlazeBinary)
            }
        }
    }

    override fun provideTargetBinaryExecPlan(executorId: String): BinaryExecutionPlan {
        val state = ReflectUtils.castFromClassName(
            this.env.runProfile,
            "com.google.idea.blaze.base.run.BlazeCommandRunConfiguration"
        ) ?: let {
            val uncastedState = ReflectUtils.getPropertyByName(it, "handler.state")
            ReflectUtils.castFromClassName(
                uncastedState,
                "com.google.idea.blaze.base.run.state.BlazeCommandRunConfigurationCommonState"
            )
        }.run {
            MirrordLogger.logger.debug("[${javaClass.name}] processStartScheduled: Bazel not detected")
            throw BuildExecPlanError("Bazel not detected")
        }

        val binaryToPatch = if (SystemInfo.isMac) {
            val binaryFromHandler = ReflectUtils.getPropertyByName(state, "handler.state.blazeBinary")?.let { blazeBinary ->
                val blazeBinary = ReflectUtils.callFunction(blazeBinary, "getBlazeBinary")
                MirrordLogger.logger.debug("[${this.javaClass.name}] processStartScheduled: found Bazel binary path in the config: $blazeBinary")
                blazeBinary as String?
            }

            val binaryPath = binaryFromHandler ?: let {
                val buildSystemProvider = ReflectUtils.callStaticFunction("com.google.idea.blaze.base.settings.Blaze", "getBuildSystemProvider(Project)", env.project)!!
                val buildSystem = ReflectUtils.getPropertyByName(buildSystemProvider, "buildSystem")!!
                val blazeContext = ReflectUtils.callStaticFunction("com.google.idea.blaze.base.scope.BlazeContext", "create")
                val buildInvoker = ReflectUtils.callFunction(buildSystem, "getBuildInvoker(Project, BlazeContext)", env.project, blazeContext)!!
                // take a look at this function since seems that should be replaced in next versions of blaze api, probably will be moved on the Runner
                ReflectUtils.callFunction(buildInvoker, "getBinaryPath") as String?
            }
            binaryPath
        } else {
            null
        }

        val originalEnv = ReflectUtils.getPropertyByName(state, "handler.state.envVars.data.myEnvs") as Map<String, String>
        MirrordLogger.logger.debug("[${javaClass.name}] found binary to patch path: $binaryToPatch")
        return BinaryExecutionPlan253(BinaryExecutionPlan241(state, binaryToPatch), originalEnv)
    }

    override fun getBinaryExecPlanClass(): KClass<out BinaryExecutionPlan> {
        return BinaryExecutionPlan253::class
    }
}

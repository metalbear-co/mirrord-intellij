package com.metalbear.mirrord.products.bazel

import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.util.SystemInfo
import com.metalbear.mirrord.MirrordExecution
import com.metalbear.mirrord.MirrordLogger
import com.metalbear.mirrord.products.bazel.BazelBinaryProvider241.BinaryExecutionPlan241
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.toImmutableMap
import kotlin.reflect.KClass

class BazelBinaryProvider253(var env: ExecutionEnvironment) : BazelBinaryProvider {

    class BinaryExecutionPlan253(private val plan: BinaryExecutionPlan241) :
        BinaryExecutionPlan {

        override fun getOriginalEnv(): ImmutableMap<String, String> {
            val env = ReflectUtils.getPropertyByName(plan.state, "handler.state.envVars.data.myEnvs") as Map<String, String>
            return env.toImmutableMap()
        }

        override fun addToEnv(map: Map<String, String>) {
            plan.addToEnv(map)
        }

        override fun getBinaryToPatch(): String? {
            return plan.getBinaryToPatch()
        }

        override fun checkExecution(executionInfo: MirrordExecution): String {
            return plan.checkExecution(executionInfo)
        }

        override fun restoreConfig(savedConfigData: SavedConfigData) {
            plan.restoreConfig(savedConfigData)
        }
    }

    override fun provideTargetBinaryExecPlan(executorId: String): BinaryExecutionPlan? {
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

        return BinaryExecutionPlan253(BinaryExecutionPlan241(state, binaryToPatch))
    }

    override fun getBinaryExecPlanClass(): KClass<out BinaryExecutionPlan> {
        return BinaryExecutionPlan253::class
    }
}

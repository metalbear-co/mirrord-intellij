package com.metalbear.mirrord.products.bazel

import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.util.SystemInfo
import com.metalbear.mirrord.MirrordExecution
import com.metalbear.mirrord.MirrordLogger
import com.metalbear.mirrord.products.bazel.BazelBinaryProvider241.BinaryExecutionPlan241
import kotlinx.collections.immutable.ImmutableMap
import kotlin.reflect.KClass

class BazelBinaryProvider253(var env: ExecutionEnvironment) : BazelBinaryProvider {

    class BinaryExecutionPlan253(private val plan: BazelBinaryProvider241.BinaryExecutionPlan241) :
        BinaryExecutionPlan {

        override fun getOriginalEnv(): ImmutableMap<String, String> {
            return plan.getOriginalEnv()
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
            ReflectUtils.getPropertyByName(state, "blazeBinaryState.blazeBinary")?.let {
                MirrordLogger.logger.debug("[${this.javaClass.name}] processStartScheduled: found Bazel binary path in the config: $it")
                it as String
            } ?: run {
                val blaze = Class.forName("com.google.idea.blaze.base.settings.Blaze")
                val buildSystemProvider = ReflectUtils.callFunction(blaze, "getBuildSystemProvider", env.project)!!
                val buildSystem = ReflectUtils.getPropertyByName(buildSystemProvider, "buildSystem")!!
                val buildInvoker = ReflectUtils.callFunction(buildSystem, "getBuildInvoker(Project)", env.project)!!
                // take a look at this function since seems that should be replaced in next versions of blaze api, probably will be moved on the Runner
                ReflectUtils.callFunction(buildInvoker, "getBinaryPath") as String?
            }
        } else {
            null
        }

        return BinaryExecutionPlan253(BinaryExecutionPlan241(state, binaryToPatch))
    }

    override fun getBinaryExecPlanClass(): KClass<out BinaryExecutionPlan> {
        return BinaryExecutionPlan253::class
    }
}

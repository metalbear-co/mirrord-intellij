package com.metalbear.mirrord.products.bazel

import com.metalbear.mirrord.MirrordExecution
import kotlinx.collections.immutable.ImmutableMap
import kotlin.reflect.KClass

class BazelBinaryProvider253: BazelBinaryProvider  {

    class BinaryExecutionPlan253 : BinaryExecutionPlan{
        override fun getOriginalEnv(): ImmutableMap<String, String> {
            TODO("Not yet implemented")
        }

        override fun addToEnv(map: Map<String, String>) {
            TODO("Not yet implemented")
        }

        override fun getBinaryToPatch(): String? {
            TODO("Not yet implemented")
        }

        override fun checkExecution(executionInfo: MirrordExecution): String {
            TODO("Not yet implemented")
        }

        override fun restoreConfig(savedConfigData: SavedConfigData) {
            TODO("Not yet implemented")
        }

    }

    override fun provideTargetBinaryExecPlan(executorId: String): BinaryExecutionPlan? {
        TODO("Not yet implemented")
    }

    override fun getBinaryExecPlanClass(): KClass<out BinaryExecutionPlan> {
        return BinaryExecutionPlan253::class
    }
}
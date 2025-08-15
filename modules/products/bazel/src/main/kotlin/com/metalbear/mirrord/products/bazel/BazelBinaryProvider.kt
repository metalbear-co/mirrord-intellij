package com.metalbear.mirrord.products.bazel

import com.intellij.execution.runners.ExecutionEnvironment
import com.metalbear.mirrord.MirrordExecution
import com.metalbear.mirrord.MirrordLogger
import kotlinx.collections.immutable.ImmutableMap
import kotlin.reflect.KClass
import kotlin.reflect.full.functions
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible

class BuildExecPlanError(message: String) : RuntimeException(message) {
    constructor(message: String, cause: Throwable) : this(message) {
        initCause(cause)
    }
}

class ExecutionCheckFailed(message: String) : Exception(message) {
    constructor(message: String, cause: Throwable) : this(message) {
        initCause(cause)
    }
}

class RestoreFailed(message: String) : Exception(message) {
    constructor(message: String, cause: Throwable) : this(message) {
        initCause(cause)
    }
}

interface BinaryExecutionPlan {
    fun getOriginalEnv() : ImmutableMap<String, String>;
    fun addToEnv(map: Map<String, String>);
    fun getBinaryToPatch() : String?;
    @Throws(ExecutionCheckFailed::class)
    fun checkExecution(executionInfo : MirrordExecution) : String;
    @Throws(RestoreFailed::class)
    fun restoreConfig(savedConfigData: SavedConfigData)
}

interface BazelBinaryProvider{
    fun provideTargetBinaryExecPlan(executorId: String) : BinaryExecutionPlan?;

    fun getBinaryExecPlanClass() : KClass<out BinaryExecutionPlan>;

    companion object Factory {

        fun fromExecutionEnv(env : ExecutionEnvironment) : BazelBinaryProvider  {
            try {
                val clazz = Class.forName("com.google.idea.blaze.base.bazel.BuildSystem")
                val getBuildInvokerExist = clazz.getMethod("getBuildInvoker") != null;
                if(getBuildInvokerExist) {
                    return BazelBinaryProvider241.fromExecutionEnv(env)
                }else{
                    throw BuildExecPlanError("Bazel binary execution plan not available for current bazel version")
                }
            } catch (e: ClassNotFoundException) {
                MirrordLogger.logger.error("[${this.javaClass.name}] processStartScheduled: usable binary execution plan not available for current bazel version")
                throw BuildExecPlanError("Bazel binary execution plan not available for current bazel version", e)
            }
        }
    }

}


fun castFromClassName(obj: Any?, className: String): Any? {
    if (obj == null) return null
    val targetClass = Class.forName(className)
    return targetClass.cast(obj)
}

fun callFunction(obj: Any, functionName: String, vararg args : Any?) : Any? {
    obj::class.functions.find { it.name == functionName }?.let {
        return it.call(obj, *args)
    } .run {
        MirrordLogger
            .logger
            .error("[REFLECTION] Function $functionName not found in ${obj::class.qualifiedName}")
        throw RuntimeException("Function $functionName not found in ${obj::class.qualifiedName}")
    }
}

fun getPropertyByName(obj: Any, propertyName: String) : Any? {

    val propertyNames = propertyName.split(".");
    var currentObj: Any? = obj;

    propertyNames.forEach { name ->
        currentObj = getFieldValue(currentObj!!, name)
    }
    return currentObj
}

fun setPropertyByName(obj: Any, propertyName: String, value: Any?) : Any? {

    val propertyNames = propertyName.split(".").toMutableList();
    val lastProperty = propertyNames.removeLast()
    var currentObj: Any? = obj;

    propertyNames.forEach { name ->
        currentObj = getFieldValue(currentObj!!, name)
    }
    setFieldValue(currentObj!!, lastProperty, value)

    return currentObj
}



private fun getFieldValue(obj: Any, fieldName: String): Any? {
    return try {
        obj::class.memberProperties.find { it.name == fieldName }?.let {
            it.isAccessible = true
            it.getter.call(obj)
        }
    } catch (e: Exception) {
        MirrordLogger
            .logger
            .debug("[REFLECTION] reflection error, fallback from kotlin reflection to java reflection: failed to get field value for $fieldName in $obj: $e")
        try {
            val field = obj.javaClass.getDeclaredField(fieldName)
            field.isAccessible = true
            field.get(obj)
        } catch (e: Exception) {
            MirrordLogger
                .logger
                .error("[REFLECTION] reflection error: failed to get field value for $fieldName in $obj: $e")
            null
        }
    }
}

private fun setFieldValue(obj: Any, fieldName: String, value: Any?): Any? {
    return try {
        obj::class.memberProperties.find { it.name == fieldName }?.let {
            if (it is kotlin.reflect.KMutableProperty1) {
                it.setter.call(obj, value)
            } else {
                val field = obj.javaClass.getDeclaredField(fieldName)
                field.isAccessible = true
                field.set(obj, value)
            }
        }
    } catch (e: Exception) {
        MirrordLogger
            .logger
            .debug("[REFLECTION] reflection error: failed to set field value for $fieldName in $obj with value $value: $e")
    }
}


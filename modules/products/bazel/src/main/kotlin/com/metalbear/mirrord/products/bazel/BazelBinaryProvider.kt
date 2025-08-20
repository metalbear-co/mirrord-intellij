package com.metalbear.mirrord.products.bazel

import com.intellij.execution.runners.ExecutionEnvironment
import com.metalbear.mirrord.MirrordExecution
import com.metalbear.mirrord.MirrordLogger
import kotlinx.collections.immutable.ImmutableMap
import kotlin.reflect.KClass
import kotlin.reflect.full.functions
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible

/**
 * Exception that should be thrown when is impossible to build a BinaryExecutionPlan, in case the current Blaze api is
 * not supported
 */
class BuildExecPlanError(message: String) : RuntimeException(message) {
    constructor(message: String, cause: Throwable) : this(message) {
        initCause(cause)
    }
}

/**
 * Exception that should be thrown when the execution check fails, meaning that some expected conditions are not
 * supported
 */
class ExecutionCheckFailed(message: String) : Exception(message) {
    constructor(message: String, cause: Throwable) : this(message) {
        initCause(cause)
    }
}

/**
 * Exception that should be thrown when the attempt to restore the old state, after an execution, fails
 */
class RestoreFailed(message: String) : Exception(message) {
    constructor(message: String, cause: Throwable) : this(message) {
        initCause(cause)
    }
}

/**
 * BinaryExecutionPlan is used to group the datas needed for an execution with mirrord
 */
interface BinaryExecutionPlan {

    /**
     * Retrieve the original env that is present in the context of the bazel build
     *
     * @return the env obtained before the execution starts
     */
    fun getOriginalEnv() : ImmutableMap<String, String>

    /**
     * Add a list of env variables to the current env, before the execution
     */
    fun addToEnv(map: Map<String, String>)

    /**
     * Get the path to the binary to patch with mirrord
     *
     * @return the path of the target executable to patch
     */
    fun getBinaryToPatch() : String?

    /**
     * Check whether the state is coherent for an execution, it returns the original bazel executable used for building
     *
     * @return the path to the bazel executable
     * @param executionInfo infos related to the current planned mirrord execution
     * @throws ExecutionCheckFailed
     */
    @Throws(ExecutionCheckFailed::class)
    fun checkExecution(executionInfo : MirrordExecution) : String

    /**
     * Restore the environment after an execution, this method should fail if something make the old configuration
     * restore impossible
     *
     * @param savedConfigData The saved environment before the execution, to be restored and the original bazel
     * execution path
     * @throws RestoreFailed
     */
    @Throws(RestoreFailed::class)
    fun restoreConfig(savedConfigData: SavedConfigData)

}

/**
 * Preserves original configuration for active Bazel runs (user environment variables and Bazel binary path)
 */
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

/**
 * Utility class made to make life easier when you have to deal with objects via reflection
 */
class ReflectUtils {
    companion object {

        /**
         * cast a class to another one using reflection, useful when you have to cast a class to another one that is
         * present in the classpath but not imported as a dependency
         */
        fun castFromClassName(obj: Any?, className: String): Any? {
            if (obj == null) return null
            val targetClass = Class.forName(className)
            return targetClass.cast(obj)
        }

        /**
         * call a method from an object using the function name and pasting the arguments
         */
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

        /**
         * get a property from an object using the property name, works with nested properties
         */
        fun getPropertyByName(obj: Any, propertyName: String) : Any? {

            val propertyNames = propertyName.split(".");
            var currentObj: Any? = obj;

            propertyNames.forEach { name ->
                currentObj = getFieldValue(currentObj!!, name)
            }
            return currentObj
        }

        /**
         * set a property from an object using the property name, works with nested properties
         */
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
                val field = obj::class.memberProperties.find { it.name == fieldName }
                if (field == null) {
                        throw RuntimeException("Field $fieldName not found in ${obj::class.qualifiedName}")
                }
                field.let {
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
                    throw e
                }
            }
        }

        /**
         * Preserves original configuration for active Bazel runs (user environment variables and Bazel binary path)
         */
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

    }
}



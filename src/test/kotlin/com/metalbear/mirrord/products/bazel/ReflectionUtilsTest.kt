package com.metalbear.mirrord.products.bazel

import groovy.lang.Tuple2
import org.junit.jupiter.api.Test

open class A {

    fun callAfun(): String {
        return "Afun"
    }

    fun callAfun(i: Int): String {
        return "$i"
    }

    fun callAfun(i1: Int, i2: Int): String {
        return "$i1,$i2"
    }
}

class ReflectionUtilsTest {

    @Test
    fun castFromClassNameTest() {
        val hashMap = HashMap<String, String>()
        hashMap["key"] = "value"

        // downcast
        val downcastedMap = ReflectUtils.castFromClassName(hashMap, "java.util.Map") as Map<String, String>
        assert(downcastedMap["key"] == "value")

        // upcast
        val upcastedMap = ReflectUtils.castFromClassName(hashMap, "java.util.HashMap") as HashMap<String, String>
        assert(upcastedMap["key"] == "value")

        // bad cast
        val exceptionThrown = try {
            ReflectUtils.castFromClassName(hashMap, "java.lang.String")
            false
        } catch (_: ClassCastException) {
            true
        }

        assert(exceptionThrown)
    }

    @Test
    fun callFunctionTest() {
        val hashMap = HashMap<String, String>()
        ReflectUtils.callFunction(hashMap, "put", "key", "value")
        assert(hashMap["key"] == "value")

        val exceptionThrown = try {
            ReflectUtils.callFunction(hashMap, "functionThatNonExist", "key", "value")
            false
        } catch (_: Throwable) {
            true
        }

        assert(exceptionThrown)

        // call overloaded function

        val anObj = A()

        val simpleCall = ReflectUtils.callFunction(anObj, "callAfun()") as String
        assert(simpleCall == "Afun")

        val overloadedCall = ReflectUtils.callFunction(anObj, "callAfun(Int)", 1) as String
        assert(overloadedCall == "1")

        val overloadedCallWithTwoParams = ReflectUtils.callFunction(anObj, "callAfun(Int,Int)", 1, 2) as String
        assert(overloadedCallWithTwoParams == "1,2")
    }

    @Test
    fun callStaticFunctionTest() {
        val time = ReflectUtils.callStaticFunction("java.lang.System", "currentTimeMillis")
        assert(time is Long)
        assert(time != null)

        val exceptionThrown = try {
            ReflectUtils.callStaticFunction("function.that.not.exist", "functionThatNonExist")
            false
        } catch (_: Throwable) {
            true
        }

        assert(exceptionThrown)
    }

    @Test
    fun getPropertyByNameTest() {
        val hashMap = HashMap<String, String>()
        hashMap.entries.size
        hashMap["key"] = "value"
        val size = ReflectUtils.getPropertyByName(hashMap, "size") as Int
        assert(size == 1)

        // with nested properties
        val entries = ReflectUtils.getPropertyByName(hashMap, "entries.size") as Int
        assert(entries == 1)

        val exceptionThrown = try {
            ReflectUtils.getPropertyByName(hashMap, "propertyThatNonExist")
            false
        } catch (_: Throwable) {
            true
        }

        assert(exceptionThrown)
    }

    @Test
    fun setPropertyByNameTest() {
        var tuple = Tuple2(1, 2)
        ReflectUtils.setPropertyByName(tuple, "v1", 3)
        assert(tuple.v1 == 3)

        // works without mutability check
        val tuple2 = Tuple2(1, 2)
        ReflectUtils.setPropertyByName(tuple2, "v1", 4)
        assert(tuple2.v1 == 4)

        val exceptionThrown = try {
            ReflectUtils.setPropertyByName(tuple, "propertyThatNonExist", 3)
            false
        } catch (_: Throwable) {
            true
        }

        assert(exceptionThrown)
    }
}

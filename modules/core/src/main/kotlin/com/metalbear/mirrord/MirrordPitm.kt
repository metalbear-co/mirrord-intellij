package com.metalbear.mirrord

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.wsl.WSLDistribution
import com.intellij.openapi.util.SystemInfo
import java.util.Base64

/**
 * True when mirrord's Windows-native injection path (`mirrord.exe pitm` for run,
 * `mirrord.exe attach` for debug) should be used. Requires an actual Windows host
 * AND no resolved WSL distribution — WSL targets use the Linux/LD_PRELOAD path
 * regardless of host OS.
 */
fun isWinNative(wsl: WSLDistribution?): Boolean =
    SystemInfo.isWindows && wsl == null

/**
 * Helper for the `mirrord pitm` (Process In The Middle) Windows injection mode.
 *
 * CLI source: https://github.com/metalbear-co/mirrord/blob/main/mirrord/cli/src/pitm.rs
 * Introduced in: https://github.com/metalbear-co/mirrord/pull/4191
 *
 * On Windows there is no `LD_PRELOAD`. `mirrord pitm` spawns the target process
 * in a suspended state, injects the layer DLL directly, then resumes it —
 * eliminating the race window that exists with post-hoc `mirrord attach`.
 *
 * Mirrord env vars (intproxy address, config, etc.) are ferried to the child
 * via a single [CHILD_ENV_VAR] containing a base64-encoded JSON payload, so
 * the pitm wrapper process itself does not inherit them.
 */
object MirrordPitm {

    /** Env var `mirrord pitm` reads to build the child process's environment. */
    const val CHILD_ENV_VAR = "MIRRORD_CHILD_ENV"

    /**
     * Wraps a [GeneralCommandLine] so that the original command is launched via
     * `mirrord pitm -- <original exe> <original args>`.
     *
     * Moves [mirrordEnvVars] from the command line's environment into a
     * `MIRRORD_CHILD_ENV` payload that `mirrord pitm` will decode and set on
     * the child process.
     */
    fun wrapCommandLine(
        commandLine: GeneralCommandLine,
        cliPath: String,
        mirrordEnvVars: Map<String, String>,
        envToUnset: List<String>?
    ) {
        MirrordLogger.logger.info(
            "MirrordPitm.wrapCommandLine: ENTER cliPath=$cliPath originalExe=${commandLine.exePath} " +
                "originalArgsCount=${commandLine.parametersList.list.size} " +
                "mirrordEnvVars=${mirrordEnvVars.size} envToUnset=${envToUnset?.size ?: 0} " +
                "cmdEnvSize=${commandLine.environment.size}"
        )

        val childEnv = encodeChildEnv(mirrordEnvVars, envToUnset)

        // Remove mirrord env vars from the command line — they belong to the child only.
        var removedCount = 0
        for (key in mirrordEnvVars.keys) {
            if (commandLine.environment.remove(key) != null) {
                removedCount++
            }
        }
        MirrordLogger.logger.debug(
            "MirrordPitm.wrapCommandLine: stripped $removedCount/${mirrordEnvVars.size} mirrord vars from wrapper env"
        )

        commandLine.withEnvironment(CHILD_ENV_VAR, childEnv)

        val originalExe = commandLine.exePath
        val originalArgs = commandLine.parametersList.list.toList()

        commandLine.exePath = cliPath
        commandLine.parametersList.clearAll()
        commandLine.addParameters("pitm", "--", originalExe)
        commandLine.addParameters(originalArgs)

        // Guardrail: mirrord env vars should only live inside MIRRORD_CHILD_ENV on the wrapper process.
        val leaked = commandLine.environment.keys.intersect(mirrordEnvVars.keys)
        if (leaked.isNotEmpty()) {
            MirrordLogger.logger.warn(
                "MirrordPitm.wrapCommandLine: LEAK — mirrord env vars still present on wrapper command line: $leaked"
            )
        }

        MirrordLogger.logger.info(
            "MirrordPitm.wrapCommandLine: SUCCESS wrapped as `$cliPath pitm -- $originalExe <${originalArgs.size} args>`, " +
                "CHILD_ENV payload.len=${childEnv.length}"
        )
    }

    /**
     * Encodes [envToSet] and [envToUnset] into the base64-JSON payload that
     * `mirrord pitm` expects in [CHILD_ENV_VAR].
     */
    fun encodeChildEnv(envToSet: Map<String, String>, envToUnset: List<String>?): String {
        val json = JsonObject()

        val setObj = JsonObject()
        for ((k, v) in envToSet) {
            setObj.addProperty(k, v)
        }
        json.add("set", setObj)

        if (!envToUnset.isNullOrEmpty()) {
            val unsetArr = JsonArray()
            for (key in envToUnset) {
                unsetArr.add(key)
            }
            json.add("unset", unsetArr)
        }

        return Base64.getEncoder().encodeToString(json.toString().toByteArray())
    }
}

package com.metalbear.mirrord.products.pycharm

import com.intellij.execution.target.TargetEnvironmentRequest
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.jetbrains.python.run.AbstractPythonRunConfiguration
import com.jetbrains.python.run.PythonExecution
import com.jetbrains.python.run.PythonRunParams
import com.jetbrains.python.run.target.HelpersAwareTargetEnvironmentRequest
import com.jetbrains.python.run.target.PythonCommandLineTargetEnvironmentProvider
import com.metalbear.mirrord.MirrordProjectService
import com.metalbear.mirrord.bifrost.MirrordEnvironments

class PythonCommandLineProvider : PythonCommandLineTargetEnvironmentProvider {
    // Wrapper for docker variant of TargetEnvironmentRequest because the variant is dynamically loaded from another
    // plugin so we need to perform our operations via reflection api
    private class DockerRuntimeConfig(val inner: TargetEnvironmentRequest) {
        var runCliOptions: String?
            get() {
                val runCliOptionsField = inner.javaClass.getDeclaredField("myRunCliOptions")
                return if (runCliOptionsField.trySetAccessible()) {
                    runCliOptionsField.get(inner) as String
                } else {
                    null
                }
            }
            set(value) {
                inner
                    .javaClass
                    .getMethod("setRunCliOptions", Class.forName("java.lang.String"))
                    .invoke(inner, value)
            }
    }

    private fun extendContainerTargetEnvironment(project: Project, runParams: PythonRunParams, docker: DockerRuntimeConfig) {
        val service = project.service<MirrordProjectService>()

        val environment = MirrordEnvironments.forProject(project)

        service.execManager.wrapper("pycharm", runParams.getEnvs(), environment).containerStart()?.let { executionInfo ->
            docker.runCliOptions?.let {
                executionInfo.extraArgs.add(it)
            }

            docker.runCliOptions = executionInfo.extraArgs.joinToString(" ")
        }
    }

    override fun extendTargetEnvironment(
        project: Project,
        helpersAwareTargetRequest: HelpersAwareTargetEnvironmentRequest,
        pythonExecution: PythonExecution,
        runParams: PythonRunParams
    ) {
        val service = project.service<MirrordProjectService>()

        if (runParams is AbstractPythonRunConfiguration<*>) {
            val docker = helpersAwareTargetRequest.targetEnvironmentRequest.let {
                if (it.javaClass.name.startsWith("com.intellij.docker")) {
                    DockerRuntimeConfig(it)
                } else {
                    null
                }
            }

            if (docker != null) {
                extendContainerTargetEnvironment(project, runParams, docker)
            } else {
                val environment = MirrordEnvironments.forRequest(project, helpersAwareTargetRequest.targetEnvironmentRequest)

                service.execManager.wrapper("pycharm", runParams.getEnvs(), environment).start()?.let { executionInfo ->
                    for (entry in executionInfo.environment.entries.iterator()) {
                        pythonExecution.addEnvironmentVariable(entry.key, entry.value)
                    }

                    for (key in executionInfo.envToUnset.orEmpty()) {
                        pythonExecution.envs.remove(key)
                    }

                    pythonExecution.addEnvironmentVariable("MIRRORD_DETECT_DEBUGGER_PORT", "pydevd")
                }
            }
        }
    }
}

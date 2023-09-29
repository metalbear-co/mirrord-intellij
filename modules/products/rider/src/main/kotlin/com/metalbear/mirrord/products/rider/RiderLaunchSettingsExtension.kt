package com.metalbear.mirrord.products.rider

import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.project.Project
import com.jetbrains.rd.util.lifetime.Lifetime
import com.jetbrains.rider.run.configurations.RiderConfigurationLaunchSettingsExtension
import com.jetbrains.rider.run.configurations.RuntimeHotReloadRunConfigurationInfo
import com.jetbrains.rider.run.configurations.launchSettings.LaunchSettingsJson
import com.metalbear.mirrord.CONFIG_ENV_NAME
import java.util.concurrent.ConcurrentHashMap

class RiderLaunchSettingsExtension : RiderConfigurationLaunchSettingsExtension {

    companion object {
        var configFromEnvs: ConcurrentHashMap<Project, String?> = ConcurrentHashMap()
    }

    override suspend fun canExecute(
        lifetime: Lifetime,
        hotReloadRunInfo: RuntimeHotReloadRunConfigurationInfo,
        profile: LaunchSettingsJson.Profile
    ): Boolean {
        return true
    }

    override fun executor(
        project: Project,
        environment: ExecutionEnvironment,
        profile: LaunchSettingsJson.Profile
    ): LaunchSettingsJson.Profile {
        val configFromEnv = profile.environmentVariables?.get(CONFIG_ENV_NAME)
        configFromEnvs.apply {
            if (configFromEnv != null) {
                put(project, configFromEnv)
            }
        }
        return profile
    }
}

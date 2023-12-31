package com.metalbear.mirrord.products.rider

import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.project.Project
import com.jetbrains.rd.util.lifetime.Lifetime
import com.jetbrains.rider.run.configurations.RiderConfigurationLaunchSettingsExtension
import com.jetbrains.rider.run.configurations.RuntimeHotReloadRunConfigurationInfo
import com.jetbrains.rider.run.configurations.launchSettings.LaunchSettingsJson
import com.metalbear.mirrord.CONFIG_ENV_NAME
import java.util.concurrent.ConcurrentHashMap

// Need this extension since setting MIRRORD_CONFIG_FILE in the project configuration
// is not visible to our current methods of getting the env.
// Both `canExecute` and `executor` have access to the project's environment variables
// and `canExecute` is called before `executor`, so just choosing `executor` at random
// to get the environment variables.
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
        profile.environmentVariables?.get(CONFIG_ENV_NAME).apply {
            this?.let { configFromEnvs[project] = it }
        }
        return profile
    }
}

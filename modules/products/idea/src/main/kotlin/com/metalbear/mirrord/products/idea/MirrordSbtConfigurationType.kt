package com.metalbear.mirrord.products.idea

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project

/**
 * Dedicated mirrord wrapper around the Scala plugin's SBT run configuration.
 *
 * This exists because shell-backed SBT runs do not reliably participate in the generic JVM
 * run-configuration extension flow that mirrord uses for normal application configurations.
 */
class MirrordSbtConfigurationType : ConfigurationType, DumbAware {
    private val configurationFactory = MirrordSbtConfigurationFactory(this)

    override fun getDisplayName(): String = "mirrord SBT"

    override fun getConfigurationTypeDescription(): String = "Run SBT application tasks through mirrord using the SBT shell"

    override fun getIcon() = AllIcons.Actions.Execute

    override fun getId(): String = "MirrordSbtRunConfiguration"

    override fun getConfigurationFactories(): Array<ConfigurationFactory> = arrayOf(configurationFactory)
}

class MirrordSbtConfigurationFactory(type: ConfigurationType) : ConfigurationFactory(type) {
    override fun createTemplateConfiguration(project: Project): RunConfiguration =
        MirrordSbtRunConfiguration(project, this, "mirrord SBT")

    override fun getId(): String = "mirrord-sbt"

    override fun getName(): String = "mirrord SBT"
}

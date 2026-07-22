import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

rootProject.name = "mirrord"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }

    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.4.0"
        id("org.jetbrains.intellij.platform") version "2.18.1"
        id("org.jetbrains.intellij.platform.module") version "2.16.0"
        id("org.jetbrains.changelog") version "2.+"
        id("org.jlleitschuh.gradle.ktlint") version "11.5.0"
    }
}

plugins {
    id("org.jetbrains.intellij.platform.settings") version "2.16.0"
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://packages.jetbrains.team/maven/p/iuia/qa-automation-maven")
        maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
        intellijPlatform {
            defaultRepositories()
        }
    }
}

include(
    "modules/core",
    "modules/products/idea",
    "modules/products/goland",
    "modules/products/pycharm",
    "modules/products/rubymine",
    "modules/products/nodejs",
    "modules/products/rider",
    "modules/products/tomcat",
    "modules/products/bazel"
)

// Rename modules to mirrord-<module>, I think this is required IntelliJ wise.
rootProject.children.forEach {
    it.name = (it.name.replaceFirst("modules/", "mirrord/").replace("/", "-"))
}

fun properties(key: String) = project.findProperty(key).toString()

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform.module")
}

dependencies {
    implementation(project(":mirrord-core"))

    intellijPlatform {
        intellijIdea(properties("platformVersion"))
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.plugins.gradle")
        bundledPlugin("org.jetbrains.idea.maven")
        plugin("org.intellij.scala", "2026.1.18")
    }
}

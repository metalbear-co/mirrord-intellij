fun properties(key: String) = project.findProperty(key).toString()

plugins {
    // Java support
    id("java")
    // Kotlin support
    id("org.jetbrains.kotlin.jvm") version "2.2.0"
    // Gradle IntelliJ Plugin
    id("org.jetbrains.intellij") version "1.+"
}

tasks {
    buildSearchableOptions {
        enabled = false
    }
}

intellij {
    version.set(properties("platformVersion"))
    plugins.set(listOf("com.google.idea.bazel.ijwb:2024.09.24.0.2-api-version-241"))
}

dependencies {
    implementation(project(":mirrord-core"))
}

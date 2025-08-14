fun properties(key: String) = project.findProperty(key).toString()

plugins {
    // Java support
    id("java")
    // Kotlin support
    id("org.jetbrains.kotlin.jvm") version "1.8.22"
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
    plugins.set(
        listOf(
            "com.google.idea.bazel.ijwb:2024.09.24.0.2-api-version-241",
            //"com.google.idea.bazel.ijwb:2025.07.24.0.1-api-version-252"
        )
    )
}

dependencies {
    implementation(project(":mirrord-core"))
}

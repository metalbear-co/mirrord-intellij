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
    plugins.set(listOf("org.jetbrains.plugins.go:241.14494.240"))
}

dependencies {
    implementation(project(":mirrord-core"))
    implementation("com.github.zafarkhaja:java-semver:0.9.0")
}

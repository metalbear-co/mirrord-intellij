fun properties(key: String) = project.findProperty(key).toString()

plugins {
    // Java support
    id("java")
    // Kotlin support
    id("org.jetbrains.kotlin.jvm") version "2.2.21"
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
    plugins.set(listOf("PythonCore:241.14494.240"))
}

dependencies {
    implementation(project(":mirrord-core"))
}

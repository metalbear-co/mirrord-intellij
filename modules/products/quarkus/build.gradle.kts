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
    type.set("IU")
    //version.set(properties("platformVersion"))
    version.set("2024.1")
    plugins.set(listOf("java", "gradle", "maven", "com.intellij.quarkus:241.14494.158"))
}

dependencies {
    implementation(project(":mirrord-core"))
}

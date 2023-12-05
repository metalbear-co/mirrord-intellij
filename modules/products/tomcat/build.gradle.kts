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
    type.set("IU")
    plugins.set(listOf("Tomcat:223.7571.182", "com.intellij.javaee.app.servers.integration"))
}

dependencies {
    implementation(project(":mirrord-core"))
}

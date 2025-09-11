fun properties(key: String) = project.findProperty(key).toString()

plugins {
    // Java support
    id("java")
    // Kotlin support
    id("org.jetbrains.kotlin.jvm") version "2.2.20"
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
    plugins.set(listOf("Tomcat:241.14494.158", "com.intellij.javaee.app.servers.integration"))
}

dependencies {
    implementation(project(":mirrord-core"))
}

fun properties(key: String) = project.findProperty(key).toString()

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform.module")
}

dependencies {
    implementation(project(":mirrord-core"))

    intellijPlatform {
        rider(properties("platformVersion")) {
            useInstaller = false
        }
    }
}

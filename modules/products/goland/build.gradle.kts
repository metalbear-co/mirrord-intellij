fun properties(key: String) = project.findProperty(key).toString()

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform.module")
}

dependencies {
    implementation(project(":mirrord-backend"))
    implementation("com.github.zafarkhaja:java-semver:0.9.0")

    intellijPlatform {
        goland(properties("platformVersion"))
        plugin("org.jetbrains.plugins.go", "241.14494.240")
    }
}

fun properties(key: String) = project.findProperty(key).toString()

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform.module")
}

dependencies {
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("com.github.zafarkhaja:java-semver:0.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.3.5")

    intellijPlatform {
        intellijIdea(properties("platformVersion"))
        // Needed by com.jetbrains.jsonSchema.*
        bundledPlugin("JavaScript")
    }
}

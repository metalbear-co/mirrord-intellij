fun properties(key: String) = project.findProperty(key).toString()

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform.module")
}

dependencies {
    implementation("com.google.code.gson:gson:2.14.0")
    implementation("com.github.zafarkhaja:java-semver:0.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.5.0")
    testImplementation("org.junit.jupiter:junit-jupiter-api:6.1.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:6.1.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.2")

    intellijPlatform {
        intellijIdea(properties("platformVersion"))
        // Needed by com.jetbrains.jsonSchema.*
        bundledPlugin("JavaScript")
    }
}

tasks.test {
    useJUnitPlatform()
}

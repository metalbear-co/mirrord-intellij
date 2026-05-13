fun properties(key: String) = project.findProperty(key).toString()

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform.module")
}

dependencies {
    implementation(project(":mirrord-core"))

    intellijPlatform {
        rubymine(properties("platformVersion"))
        plugin("org.jetbrains.plugins.ruby", "241.14494.240")
    }
}

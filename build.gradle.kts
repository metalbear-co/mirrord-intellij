import org.gradle.process.ProcessForkOptions
import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.nio.file.Paths
import java.util.EnumSet

fun properties(key: String) = project.findProperty(key).toString()
val platformVersion = providers.gradleProperty("platformVersion").get()

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
    id("org.jlleitschuh.gradle.ktlint")
}

group = properties("pluginGroup")
version = properties("pluginVersion")

val remoteRobotVersion = "0.11.19"
val platformType = System.getenv("PLATFORMTYPE") ?: "IU"

repositories {
    mavenCentral()
    maven("https://packages.jetbrains.team/maven/p/iuia/qa-automation-maven")
    maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    testImplementation("com.intellij.remoterobot:remote-robot:$remoteRobotVersion")
    testImplementation("com.intellij.remoterobot:remote-fixtures:$remoteRobotVersion")
    testImplementation("com.intellij.remoterobot:ide-launcher:0.11.19.414")
    testImplementation("com.automation-remarks:video-recorder-junit5:2.0")
    testImplementation("org.junit.jupiter:junit-jupiter-api:6.1.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:6.1.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.0")
    // BasePlatformTestCase is JUnit3; the other tests are JUnit5.
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:6.1.0")
    testImplementation("com.squareup.okhttp3:logging-interceptor:5.4.0")
    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        create(platformType, properties("platformVersion"))

        pluginComposedModule(implementation(project(":mirrord-products-idea")))
        pluginComposedModule(implementation(project(":mirrord-products-pycharm")))
        pluginComposedModule(implementation(project(":mirrord-products-rubymine")))
        pluginComposedModule(implementation(project(":mirrord-products-goland")))
        pluginComposedModule(implementation(project(":mirrord-products-nodejs")))
        pluginComposedModule(implementation(project(":mirrord-products-rider")))
        pluginComposedModule(implementation(project(":mirrord-products-tomcat")))
        pluginComposedModule(implementation(project(":mirrord-products-bazel")))

        if (platformType !in setOf("PY", "PC", "GO", "RD", "RM")) {
            properties("platformPlugins")
                .split(',')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .forEach { bundledPlugin(it) }
        }

        testFramework(TestFrameworkType.Platform)
    }
}

subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
}

allprojects {
    properties("javaVersion").let {
        tasks.withType<JavaCompile>().configureEach {
            sourceCompatibility = it
            targetCompatibility = it
        }

        tasks.withType<KotlinCompile>().configureEach {
            compilerOptions {
                jvmTarget.set(JvmTarget.fromTarget(it))
            }
        }
    }
}

gradle.taskGraph.whenReady(
    closureOf<TaskExecutionGraph> {
        val ignoreSubprojectTasks = listOf(
            "buildSearchableOptions",
            "publishPlugin",
            "runIde",
            "verifyPlugin"
        )

        // Don't run some tasks for subprojects
        for (task in allTasks) {
            if (task.project != task.project.rootProject && task.name in ignoreSubprojectTasks) {
                task.enabled = false
            }
        }
    }
)

changelog {
    version.set(properties("pluginVersion"))
    groups.set(listOf("Added", "Changed", "Deprecated", "Removed", "Fixed", "Security", "Internal"))
}

intellijPlatform {
    pluginConfiguration {
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = "253"
        }

        description = providers.fileContents(layout.projectDirectory.file("README.md")).asText.map {
            val start = "<!-- Plugin description -->"
            val end = "<!-- Plugin description end -->"

            with(it.lines()) {
                if (!containsAll(listOf(start, end))) {
                    throw GradleException("Plugin description section not found in README.md:\n$start ... $end")
                }
                subList(indexOf(start) + 1, indexOf(end)).joinToString("\n").let(::markdownToHTML)
            }
        }

        if (!System.getenv("CI_BUILD_PLUGIN").toBoolean()) {
            val changelog = project.changelog
            changeNotes = providers.gradleProperty("pluginVersion").map { pluginVersion ->
                with(changelog) {
                    renderItem(
                        getOrNull(pluginVersion) ?: getLatest(),
                        Changelog.OutputType.HTML
                    )
                }
            }
        }
    }

    pluginVerification {
        failureLevel = EnumSet.of(VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS, VerifyPluginTask.FailureLevel.INVALID_PLUGIN)
    }

    signing {
        certificateChain = System.getenv("CERTIFICATE_CHAIN")
        privateKey = System.getenv("PRIVATE_KEY")
        password = System.getenv("PRIVATE_KEY_PASSWORD")
    }
}

intellijPlatformTesting {
    runIde {
        register("runIdeForUiTests") {
            task {
                // Set the port for RemoteRobot to communicate with the IDE
                jvmArgumentProviders.add(
                    CommandLineArgumentProvider {
                        listOf(
                            "-Drobot-server.port=8082",
                            "-Drobot-server.host.public=true",
                            "-Dide.mac.message.dialogs.as.sheets=false",
                            "-Djb.privacy.policy.text=\"<!--999.999-->\"",
                            "-Djb.consents.confirmation.enabled=false",
                            "-Didea.trust.all.projects=true",
                            "-Dide.show.tips.on.startup.default.value=false"
                        )
                    }
                )
            }

            plugins {
                robotServerPlugin()
            }
        }
    }
}

tasks {
    wrapper {
        gradleVersion = properties("gradleVersion")
    }

    prepareSandbox {
        val binaries = listOf("macos/arm64/dlv", "macos/x86-64/dlv")
        binaries.forEach { binary ->
            from(file(project.projectDir.resolve("bin").resolve(binary))) {
                into(Paths.get(properties("pluginName"), "bin", binary).parent.toString())
            }
        }
    }

    publishPlugin {
        dependsOn(patchChangelog)
        token.set(System.getenv("PUBLISH_TOKEN"))
        // pluginVersion is based on the SemVer (https://semver.org) and supports pre-release labels, like 2.1.7-alpha.3
        // Specify pre-release label to publish the plugin in a custom Release Channel automatically. Read more:
        // https://plugins.jetbrains.com/docs/intellij/deployment.html#specifying-a-release-channel
        channels.set(listOf("beta"))
        channels.set(listOf(properties("pluginVersion").split('-').getOrElse(1) { "default" }.split('.').first()))
    }

    test {
        useJUnitPlatform()
        systemProperty("test.workspace", projectDir.resolve("test-workspace").absolutePath)
        val pluginFileName = properties("pluginName") + "-" + properties("pluginVersion") + ".zip"
        systemProperty("test.plugin.path", projectDir.resolve("build/distributions/$pluginFileName").absolutePath)
        testLogging {
            showStandardStreams = true
            events("passed", "skipped", "failed")
        }
    }
}

tasks.matching { it.name == "buildSearchableOptions" }.configureEach {
    enabled = false
}

tasks.matching { it.name == "runIde" }.configureEach {
    doFirst {
        (this as? ProcessForkOptions)?.environment("PLUGIN_TESTING_ENVIRONMENT", "true")
    }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

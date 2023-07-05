package com.metalbear.mirrord

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.launcher.Ide
import com.intellij.remoterobot.launcher.IdeDownloader
import com.intellij.remoterobot.launcher.IdeLauncher
import com.intellij.remoterobot.utils.waitForIgnoringError
import com.intellij.remoterobot.stepsProcessing.step
import com.metalbear.mirrord.utils.*
import java.awt.Point
import java.time.Duration
import java.net.URL
import java.time.Duration.ofMinutes
import okhttp3.OkHttpClient
import org.junit.jupiter.api.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

@Timeout(value = 15, unit = TimeUnit.MINUTES)
internal class MirrordPluginTest {
    companion object {
        private var ideaProcess: Process? = null
        private var tmpDir: Path = Files.createTempDirectory("launcher")
        private lateinit var remoteRobot: RemoteRobot

        @BeforeAll
        @JvmStatic
        fun startIdea() {
            val client = OkHttpClient()
            remoteRobot = RemoteRobot("http://localhost:8082", client)
            val ideDownloader = IdeDownloader(client)
            val pluginPath = Paths.get(System.getProperty("test.plugin.path"))
            ideaProcess = IdeLauncher.launchIde(
                ideDownloader.downloadAndExtractLatestEap(Ide.PYCHARM, tmpDir),
                mapOf(
                    "robot-server.port" to 8082,
                    "idea.trust.all.projects" to true,
                    "robot-server.host.public" to true,
                    "jb.privacy.policy.text" to "<!--999.999-->",
                    "jb.consents.confirmation.enabled" to false
                ),
                emptyList(),
                listOf(ideDownloader.downloadRobotPlugin(tmpDir), pluginPath),
                tmpDir
            )
            waitForIgnoringError(ofMinutes(3)) { remoteRobot.callJs("true") }
        }

        @AfterAll
        @JvmStatic
        fun cleanUp() {
            ideaProcess?.destroy()
            tmpDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun testMirrordFlow() {
        step("Welcome Frame") {
            remoteRobot.welcomeFrame {
                openProject(System.getProperty("test.workspace"))
            }
        }
        remoteRobot.idea {
            // intellij shows tip of the day randomly
            closeTipOfTheDay()

            step("Setup Poetry Environment") {
                try {
                    dialog("Setting Up Poetry Environment") {
                        button("OK").click()
                    }
                } catch (e: Exception) {
                    // in case of a re-run, the environment is already setup
                    println("Poetry Environment already setup")
                }
            }

            step("Enable mirrord and create config file") {
                dumbAware {
                    enableMirrord.click()
                    createMirrordConfig.click()
                }
                remoteRobot.editorTabs {
                    checkFileOpened("mirrord.json")
                }
            }

            dumbAware {
                // sometimes there can be a state where we try to click
                // on a file in the projectViewTree, but it clicks on a different one
                // waiting for indexing to finish fixes this
                step("Set breakpoint") {
                    with(projectViewTree) {
                        findText("app.py").doubleClick()
                        remoteRobot.editorTabs {
                            checkFileOpened("app.py")
                        }
                    }
                }
                with(textEditor().gutter) {
                    val lineNumberPoint = findText("8").point
                    click(Point(lineNumberPoint.x + 5, lineNumberPoint.y))
                }
            }

            step("Start Debugging") {
                startDebugging.click()
                step("Select pod to mirror traffic from") {
                    dialog("mirrord", Duration.ofSeconds(120)) {
                        val podToSelect = System.getenv("POD_TO_SELECT")
                        findText(podToSelect).click()
                        button("OK").click()
                    }
                }
                runnerTabDebugger.click()
                // in the debugger tab of the xdebugger window, if the session has started
                // it displays "Connected"
                // following just checks if "Connected" is displayed
                debuggerConnected
            }

            step("Send traffic to pod") {
                val kubeService = System.getenv("KUBE_SERVICE")
                URL(kubeService).readText()
            }

            step("Assert breakpoint is hit") {
                // there is no simple way to find the blue hover of the breakpoint line
                // but if the breakpoint is hit, the debugger frames list is populated
                xDebuggerFramesList
            }
            stopDebugging.click()
        }
    }
}
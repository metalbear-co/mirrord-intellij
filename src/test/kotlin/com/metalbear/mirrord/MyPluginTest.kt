package com.metalbear.mirrord

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.launcher.Ide
import com.intellij.remoterobot.launcher.IdeDownloader
import com.intellij.remoterobot.launcher.IdeLauncher
import com.intellij.remoterobot.utils.waitForIgnoringError
import com.intellij.remoterobot.utils.waitFor
import com.intellij.remoterobot.stepsProcessing.step
import com.intellij.remoterobot.utils.keyboard
import com.intellij.util.io.exists
import com.metalbear.mirrord.utils.*
import java.awt.Point
import java.time.Duration
import java.net.URL
import java.time.Duration.ofMinutes
import okhttp3.OkHttpClient
import org.junit.jupiter.api.*
import java.awt.event.KeyEvent.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.extension.TestWatcher
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext
import java.io.File
import javax.imageio.ImageIO

@ExtendWith(MirrordPluginTest.IdeTestWatcher::class)
@Timeout(value = 15, unit = TimeUnit.MINUTES)
internal class MirrordPluginTest {
    companion object {
        private var ideaProcess: Process? = null
        private var tmpDir: Path = Files.createTempDirectory("launcher")
        private lateinit var remoteRobot: RemoteRobot
        private val poetryDialog = !Paths.get(System.getProperty("test.workspace"), ".idea").exists()

        @BeforeAll
        @JvmStatic
        fun startIdea() {
            val client = OkHttpClient()
            remoteRobot = RemoteRobot("http://localhost:8082", client)
            val ideDownloader = IdeDownloader(client)
            val pluginPath = Paths.get(System.getProperty("test.plugin.path"))
            println("downloading IDE...")
            ideaProcess = IdeLauncher.launchIde(
                ideDownloader.downloadAndExtract(Ide.PYCHARM, tmpDir, Ide.BuildType.RELEASE),
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
            println("waiting for IDE...")
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
    fun testMirrordFlow() = with(remoteRobot) {
        step("Welcome Frame") {
            welcomeFrame {
                openProject(System.getProperty("test.workspace"))
            }
        }
        idea {
            // intellij shows tip of the day randomly
            closeTipOfTheDay()

            step("Open `app.py`") {
                // if .idea exists the IDE does not provide the dialog to set up Poetry environment
                if (poetryDialog) {
                    step("Set up Poetry Environment") {
                        dialog("Setting Up Poetry Environment") {
                            button("OK").click()
                        }
                    }
                }

                // Note: Press Ctrl + Shift + N on Windows/Linux, ⌘ + ⇧ + O on macOS to invoke the Navigate to file pop-up.
                dumbAware {
                    keyboard {
                        if (remoteRobot.isMac()) {
                            hotKey(VK_SHIFT, VK_META, VK_O)
                        } else {
                            hotKey(VK_SHIFT, VK_CONTROL, VK_N)
                        }
                        enterText("app.py")
                        enter()
                    }


                    editorTabs {
                        waitFor {
                            isFileOpened("app.py")
                        }
                    }
                }

                if (!poetryDialog) {
                    step("Set up Poetry Environment") {
                        fileIntention {
                            val setUpPoetry = setUpPoetry
                            setUpPoetry.click()
                            waitFor {
                                !setUpPoetry.isShowing
                            }
                        }
                    }
                }

                step("Set breakpoint on line 8") {
                    with(textEditor().gutter) {
                        val lineNumberPoint = findText("8").point
                        click(Point(lineNumberPoint.x + 5, lineNumberPoint.y))
                    }
                }
            }


            step("Enable mirrord and create config file") {
                waitFor(Duration.ofSeconds(30)) {
                    enableMirrord.isShowing
                    createMirrordConfig.isShowing
                }
                dumbAware {
                    enableMirrord.click()
                    createMirrordConfig.click()
                }
                editorTabs {
                    waitFor {
                        isFileOpened("mirrord.json")
                    }
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
                waitFor {
                    xDebuggerFramesList.isShowing
                }
            }
            stopDebugging.click()
        }
    }

    class IdeTestWatcher : TestWatcher {
        override fun testFailed(context: ExtensionContext, cause: Throwable?) {
            ImageIO.write(remoteRobot.getScreenshot(), "png", File("build/reports", "${context.displayName}.png"))
        }
    }

}
package com.metalbear.mirrord

import com.automation.remarks.junit5.Video
import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.fixtures.ContainerFixture
import com.intellij.remoterobot.fixtures.GutterFixture
import com.intellij.remoterobot.launcher.Ide
import com.intellij.remoterobot.launcher.IdeDownloader
import com.intellij.remoterobot.launcher.IdeLauncher
import com.intellij.remoterobot.steps.CommonSteps
import com.intellij.remoterobot.stepsProcessing.step
import com.intellij.remoterobot.utils.waitFor
import com.intellij.remoterobot.utils.waitForIgnoringError
import com.metalbear.mirrord.utils.*
import okhttp3.OkHttpClient
import org.junit.jupiter.api.*
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.TestWatcher
import java.awt.Point
import java.awt.event.KeyEvent.*
import java.io.File
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration.ofMinutes
import java.time.Duration.ofSeconds
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

@ExtendWith(MirrordPluginTest.IdeTestWatcher::class)
@Timeout(value = 15, unit = TimeUnit.MINUTES)
internal class MirrordPluginTest {
    init {
        StepsLogger.init()
    }
    companion object {
        private var ideaProcess: Process? = null
        private var tmpDir: Path = Files.createTempDirectory("launcher")
        private lateinit var remoteRobot: RemoteRobot
        private var steps: CommonSteps? = null

        @BeforeAll
        @JvmStatic
        fun startIdea() {
            val client = OkHttpClient()
            remoteRobot = RemoteRobot("http://localhost:8082", client)
            steps = CommonSteps(remoteRobot)
            val ideDownloader = IdeDownloader(client)
            val pluginPath = Paths.get(System.getProperty("test.plugin.path"))
            println("downloading IDE...")
            ideaProcess = IdeLauncher.launchIde(
                ideDownloader.downloadAndExtract(Ide.PYCHARM_COMMUNITY, tmpDir, Ide.BuildType.RELEASE),
                mapOf(
                    "robot-server.port" to 8082,
                    "idea.trust.all.projects" to true,
                    "robot-server.host.public" to true,
                    "ide.show.tips.on.startup.default.value" to false
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
            val xpathPage = URL("http://localhost:8082/").readText()
            // create a file in build/reports
            val file = File("build/reports/robot-page.html")
            file.writeText(xpathPage)
            ideaProcess?.destroy()
            tmpDir.toFile().deleteRecursively()
        }
    }

    @Test
    @Video
    fun testMirrordFlow() = with(remoteRobot) {
        step("Welcome Frame") {
            welcomeFrame {
                steps?.openProject(System.getProperty("test.workspace"))
            }
        }
        idea {
            step("Open `app.py`") {
                // sometimes the projectViewTree does not open, this is quite random
                // as a workaround we try to open it again
                var clickToOpenProject = false
                val projectViewTree = try {
                    projectViewTree
                } catch (e: Exception) {
                    leftStripe {
                        findText("Project").click()
                    }
                    clickToOpenProject = true
                    projectViewTree
                }
                with(projectViewTree) {
                    if (clickToOpenProject) {
                        waitFor(ofSeconds(30)) {
                            hasText("test-workspace")
                        }
                        findText("test-workspace").doubleClick()
                    }
                    waitFor(ofSeconds(30)) {
                        hasText("app.py")
                    }
                    findText("app.py").doubleClick()
                    editorTabs {
                        waitFor {
                            isFileOpened("app.py")
                        }
                    }
                }
                step("Set up Poetry Environment") {
                    // blue stripe appears on top of the text window asking
                    // to set up poetry environment, we click on setup poetry
                    // option to quickly set up the environment
                    fileIntention {
                        val setUpPoetry = setUpPoetry
                        setUpPoetry.click()
                        waitFor {
                            !setUpPoetry.isShowing
                        }
                    }
                    statusBar {
                        // need to make sure poetry is not doing anything
                        try {
                            waitFor {
                                !poetryProgress.isShowing
                            }
                        } catch (e: Exception) {
                            // ignore because probably the progress bar is not showing
                            // and all poetry setup is done
                        }
                    }
                }

                step("Enable mirrord and create config file") {
                    waitFor(ofSeconds(30)) {
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
                        findText("app.py").click()
                        waitFor {
                            isFileOpened("app.py")
                        }
                    }
                }

                step("Set breakpoint on line 8") {
                    with(textEditor()) {
                        // there is space on the right side of the line numbers
                        // where we can click to set a breakpoint
                        // thanks to Eugene Nizienko on intellij slack for this tip
                        val gutter = find<ContainerFixture>(GutterFixture.locator, ofSeconds(30))
                        val lineNumberPoint = gutter.findText("8").point
                        gutter.click(Point(lineNumberPoint.x + 5, lineNumberPoint.y))
                    }
                }
            }

            step("Start Debugging") {
                dumbAware {
                    startDebugging.click()
                }
                step("Select pod to mirror traffic from") {
                    dialog("mirrord", ofSeconds(120)) {
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

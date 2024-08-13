package com.metalbear.mirrord

import com.automation.remarks.junit5.Video
import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.fixtures.ContainerFixture
import com.intellij.remoterobot.fixtures.GutterFixture
import com.intellij.remoterobot.launcher.Ide
import com.intellij.remoterobot.launcher.IdeDownloader
import com.intellij.remoterobot.launcher.IdeLauncher
import com.intellij.remoterobot.launcher.Os
import com.intellij.remoterobot.steps.CommonSteps
import com.intellij.remoterobot.stepsProcessing.step
import com.intellij.remoterobot.utils.waitFor
import com.intellij.remoterobot.utils.waitForIgnoringError
import com.metalbear.mirrord.utils.*
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.TestWatcher
import java.awt.Point
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
            val pathToIde = ideDownloader.downloadAndExtract(
                Ide.PYCHARM_COMMUNITY,
                tmpDir,
                Ide.BuildType.RELEASE,
                "2022.3.2"
            )

            // IdeLauncher fails when the IDE bin directory does not contain exactly one `.vmoptions` file for 64 arch.
            println("fixing vmoptions files...")
            val ideBinDir = pathToIde.resolve(
                when (Os.hostOS()) {
                    Os.MAC -> "Contents/bin"
                    else -> "bin"
                }
            )
            Files
                .list(ideBinDir)
                .filter {
                    val filename = it.fileName.toString()
                    filename.endsWith(".vmoptions") && filename.contains("64") && filename != "pycharm64.vmoptions"
                }
                .forEach {
                    println("Deleting problematic file $it")
                    Files.delete(it)
                }
            ideaProcess = IdeLauncher.launchIde(
                pathToIde,
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
            val xpathPageJs = URL("http://localhost:8082/xpathEditor.js").readText()
            val xpathPageCss = URL("http://localhost:8082/styles.css").readText()
            // create a file in build/reports
            File("build/reports/robot-page.html").writeText(xpathPage)
            File("build/reports/xpathEditor.js").writeText(xpathPageJs)
            File("build/reports/styles.css").writeText(xpathPageCss)

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
            step("Close usage banner") {
                usageBanner.findText("Close").click()
            }
            step("Create config file") {
                waitFor(ofSeconds(60)) {
                    mirrordDropdownButton.isShowing
                    // issue here is that elements move when git is visible
                    git.isShowing
                }
                // as per the extension this doesn't need to be in the dumbAware block
                // however, there can be a loading page which can only be ignored by the
                // dumbAware block
                dumbAware {
                    mirrordDropdownButton.click()
                }

                waitFor(ofSeconds(30)) {
                    mirrordDropdownMenu.isShowing
                }

                mirrordDropdownMenu.findText("Settings").click()

                editorTabs {
                    waitFor(ofSeconds(60)) {
                        isFileOpened("mirrord.json")
                    }
                }
            }

            step("Open `app.py`") {
                openFile("app.py")
                editorTabs {
                    waitFor {
                        isFileOpened("app.py")
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
                        // wait for the progress bar to disappear - poetry is set up
                        try {
                            val progressIcon = progressIcon
                            waitFor(ofSeconds(120)) {
                                !progressIcon.isShowing
                            }
                        } catch (e: Exception) {
                            waitForProgressFinished(ofSeconds(120))
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

            step("Enable mirrord and start debugging") {
                waitFor(ofSeconds(30)) {
                    enableMirrord.isShowing
                    startDebugging.isShowing
                }
                enableMirrord.click()
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

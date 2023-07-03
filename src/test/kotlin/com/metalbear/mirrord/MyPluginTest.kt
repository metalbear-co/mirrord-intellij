package com.metalbear.mirrord

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.utils.waitForIgnoringError
import java.net.URL
import com.intellij.remoterobot.stepsProcessing.step
import com.metalbear.mirrord.utils.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.awt.Point
import java.time.Duration.ofMinutes

@ExtendWith(RemoteRobotExtension::class)
internal class MirrordPluginTest {

    private val defaultConfigFilePath = ".mirrord/mirrrord.json"

    @BeforeEach
    fun waitForIde(remoteRobot: RemoteRobot) {
        waitForIgnoringError(ofMinutes(3)) { remoteRobot.callJs("true") }
    }

    @Test
    fun testMirrordFlow(remoteRobot: RemoteRobot) = with(remoteRobot) {
        step("Welcome Frame") {
            createTestWorkspace(remoteRobot)
        }
        idea {
            closeTipOfTheDay()
            step("Setup Poetry Environment") {
                dialog("Setting Up Poetry Environment") {
                    button("OK").click()
                }
            }

            step("Enable mirrord and create config file") {
                enableMirrord.click()
                createMirrordConfig.click()
                editorTab {
                    checkFileOpened(defaultConfigFilePath)
                    checkFileExists(defaultConfigFilePath)
                }
            }

            step("Set breakpoint") {
                with(projectViewTree) {
                    findText("app.py").doubleClick()
                    editorTab {
                        checkFileOpened("app.py")
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
                    dialog("mirrord") {
                        val podToSelect = System.getenv("POD_TO_SELECT")
                        findText(podToSelect).click()
                        button("OK").click()
                    }
                }
                runnerTabDebugger.click()
                debuggerConnected
            }

            step("Send traffic to pod") {
                val kubeService = System.getenv("KUBE_SERVICE")
                URL(kubeService).readText()
            }

            step("Assert breakpoint is hit") {
                xDebuggerFramesList
            }
        }
    }

    private fun createTestWorkspace(remoteRobot: RemoteRobot) = with(remoteRobot) {
        // TODO: fix this by using openProject
        // https://github.com/JetBrains/intellij-ui-test-robot/blob/a9f5958ce6434bffef14fa52f3c823a27e73360e/remote-fixtures/
        // src/main/kotlin/com/intellij/remoterobot/steps/CommonSteps.kt#L51
        welcomeFrame {
            createNewProjectFromVCS.click()
            dialog("Get from Version Control") {
                textField("URL:", true).text = "https://github.com/infiniteregrets/test-workspace"
                button("Clone").click()
            }
        }
    }
}
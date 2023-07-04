package com.metalbear.mirrord

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.utils.waitForIgnoringError
import com.intellij.remoterobot.stepsProcessing.step
import com.metalbear.mirrord.utils.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.awt.Point
import java.time.Duration
import java.net.URL
import java.time.Duration.ofMinutes

@ExtendWith(RemoteRobotExtension::class)
internal class MirrordPluginTest {

    @BeforeEach
    fun waitForIde(remoteRobot: RemoteRobot) {
        waitForIgnoringError(ofMinutes(3)) { remoteRobot.callJs("true") }
    }

    @Test
    fun testMirrordFlow(remoteRobot: RemoteRobot) = with(remoteRobot) {
        step("Welcome Frame") {
            createTestWorkspace(remoteRobot)
        }
//        idea {
//            closeTipOfTheDay()
//
//            step("Setup Poetry Environment") {
//                try {
//                    dialog("Setting Up Poetry Environment") {
//                        button("OK").click()
//                    }
//                } catch (e: Exception) {
//                    // in case of a re-run, the environment is already setup
//                    println("Poetry Environment already setup")
//                }
//            }
//
//            step("Enable mirrord and create config file") {
//                enableMirrord.click()
//                createMirrordConfig.click()
//                editorTabs {
//                    checkFileOpened("mirrord.json")
//                }
//            }
//
//            dumbAware {
//                step("Set breakpoint") {
//                    with(projectViewTree) {
//                        findText("app.py").doubleClick()
//                        editorTabs {
//                            checkFileOpened("app.py")
//                        }
//                    }
//                }
//                with(textEditor().gutter) {
//                    val lineNumberPoint = findText("8").point
//                    click(Point(lineNumberPoint.x + 5, lineNumberPoint.y))
//                }
//            }
//
//            step("Start Debugging") {
//                startDebugging.click()
//                step("Select pod to mirror traffic from") {
//                    dialog("mirrord", Duration.ofSeconds(120)) {
//                        val podToSelect = System.getenv("POD_TO_SELECT")
//                        findText(podToSelect).click()
//                        button("OK").click()
//                    }
//                }
//                runnerTabDebugger.click()
//                debuggerConnected
//            }
//
//            step("Send traffic to pod") {
//                val kubeService = System.getenv("KUBE_SERVICE")
//                URL(kubeService).readText()
//            }
//
//            step("Assert breakpoint is hit") {
//                xDebuggerFramesList
//            }
//        }
    }

    private fun createTestWorkspace(remoteRobot: RemoteRobot) = with(remoteRobot) {
        welcomeFrame {
            openProject(System.getProperty("test.workspace"))
        }
    }
}
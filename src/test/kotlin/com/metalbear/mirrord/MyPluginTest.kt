package com.metalbear.mirrord

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.utils.Keyboard
import com.intellij.remoterobot.utils.waitForIgnoringError
import com.metalbear.mirrord.utils.RemoteRobotExtension
import com.metalbear.mirrord.utils.dialog
import com.metalbear.mirrord.utils.idea
import com.metalbear.mirrord.utils.welcomeFrame
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.awt.Point
import java.awt.event.KeyEvent
import java.time.Duration.ofMinutes

@ExtendWith(RemoteRobotExtension::class)
internal class MirrordPluginTest {

    private lateinit var keyboard: Keyboard

    @BeforeEach
    fun waitForIde(remoteRobot: RemoteRobot) {
        waitForIgnoringError(ofMinutes(3)) { remoteRobot.callJs("true") }
    }

    @Test
    fun testMirrordFlow(remoteRobot: RemoteRobot) = with(remoteRobot) {
        createTestWorkspace(remoteRobot)
        idea {
            closeTipOfTheDay()
            dialog("Setting Up Poetry Environment") {
                button("OK").click()
            }
            with(projectViewTree) {
                findText("app.py").doubleClick()
            }
            goToLineAndColumn(8, 0, remoteRobot)
            setBreakPoint(remoteRobot)
            with(textEditor().gutter) {
                val lineNumberPoint = findText("8").point
                click(Point(lineNumberPoint.x + 5, lineNumberPoint.y))
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

    private fun goToLineAndColumn(row: Int, column: Int, remoteRobot: RemoteRobot) = with(remoteRobot) {
        if (isMac()) keyboard.hotKey(
            KeyEvent.VK_META,
            KeyEvent.VK_L
        ) else keyboard.hotKey(KeyEvent.VK_CONTROL, KeyEvent.VK_G)
        keyboard.enterText("$row:$column")
        keyboard.enter()
    }

    private fun setBreakPoint(remoteRobot: RemoteRobot) = with(remoteRobot) {
        if (isMac()) keyboard.hotKey(
            KeyEvent.VK_META,
            KeyEvent.VK_F8
        ) else keyboard.hotKey(KeyEvent.VK_CONTROL, KeyEvent.VK_F8)
    }
}
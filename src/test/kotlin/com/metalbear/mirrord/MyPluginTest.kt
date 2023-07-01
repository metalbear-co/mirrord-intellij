package com.metalbear.mirrord

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.utils.waitForIgnoringError
import com.metalbear.mirrord.utils.RemoteRobotExtension
import com.metalbear.mirrord.utils.dialog
import com.metalbear.mirrord.utils.idea
import com.metalbear.mirrord.utils.welcomeFrame
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.time.Duration.ofMinutes

@ExtendWith(RemoteRobotExtension::class)
internal class MirrordPluginTest {
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
        }
    }

    private fun createTestWorkspace(remoteRobot: RemoteRobot) = with(remoteRobot) {
        welcomeFrame {
            createNewProjectFromVCS.click()
            dialog("Get from Version Control") {
                textField("URL:", true).text = "https://github.com/infiniteregrets/test-workspace"
                button("Clone").click()
            }
        }
    }
}
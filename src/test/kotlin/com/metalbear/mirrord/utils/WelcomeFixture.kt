package com.metalbear.mirrord.utils

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.data.RemoteComponent
import com.intellij.remoterobot.fixtures.*
import com.intellij.remoterobot.search.locators.byXpath
import com.intellij.remoterobot.utils.waitFor
import java.time.Duration

// Note: some implementation is taken from the example provided at https://github.com/JetBrains/intellij-ui-test-robot

fun RemoteRobot.welcomeFrame(function: WelcomeFrame.() -> Unit) {
    find(WelcomeFrame::class.java, Duration.ofSeconds(60)).apply(function)
}

fun RemoteRobot.closeMeetTheIslandsTheme() {
    val titleLocator = byXpath("//div[@visible_text='Meet the Islands Theme']")
    val skipLocator = byXpath("//div[@class='ActionLink' and @visible_text='Skip']")

    waitFor(Duration.ofSeconds(30)) {
        findAll<ContainerFixture>(titleLocator).any { it.isShowing }
    }

    val skip = find<ContainerFixture>(skipLocator, Duration.ofSeconds(30))
    skip.click()
    waitFor(Duration.ofSeconds(30)) {
        findAll<ContainerFixture>(titleLocator).none { it.isShowing }
    }
}

// represents the first "welcome" window asking to create a new project or open an existing one
@FixtureName("Welcome Frame")
@DefaultXpath(
    "Welcome IdeFrame",
    "//div[@class='IdeFrameImpl' and (contains(@accessiblename, 'Welcome - PyCharm') or contains(@title, 'Welcome'))]"
)
class WelcomeFrame(remoteRobot: RemoteRobot, remoteComponent: RemoteComponent) :
    CommonContainerFixture(remoteRobot, remoteComponent)

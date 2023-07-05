package com.metalbear.mirrord.utils

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.data.RemoteComponent
import com.intellij.remoterobot.fixtures.*
import com.intellij.remoterobot.search.locators.byXpath
import com.intellij.remoterobot.stepsProcessing.step
import com.intellij.remoterobot.utils.waitFor
import java.time.Duration

// Note: some implementation is taken from the example provided at https://github.com/JetBrains/intellij-ui-test-robot

fun RemoteRobot.idea(function: IdeaFrame.() -> Unit) {
    find<IdeaFrame>(timeout = Duration.ofSeconds(10)).apply(function)
}

@FixtureName("Idea frame")
@DefaultXpath("IdeFrameImpl type", "//div[@class='IdeFrameImpl']")
class IdeaFrame(remoteRobot: RemoteRobot, remoteComponent: RemoteComponent) :
    CommonContainerFixture(remoteRobot, remoteComponent) {

    val menuBar: JMenuBarFixture
        get() = find(JMenuBarFixture::class.java, JMenuBarFixture.byType())

    val projectViewTree
        get() = find<ContainerFixture>(byXpath("ProjectViewTree", "//div[@class='ProjectViewTree']"))

    val enableMirrord
        get() = find<ContainerFixture>(byXpath("//div[@myicon='mirrord.svg']"), Duration.ofSeconds(30))
    val createMirrordConfig
        get() = find<ContainerFixture>(
            byXpath(
                "//div[@accessiblename='Open mirrord configuration file' " +
                        "and @class='ActionButton' " +
                        "and @myaction='Open mirrord configuration file (Opens/creates the default mirrord configuration file)']"
            ),
            Duration.ofSeconds(30)
        )

    val startDebugging
        get() = find<ContainerFixture>(
            byXpath("//div[@class='ActionButton' and @myaction='Debug (Debug selected configuration)']")
        )

    val stopDebugging
        get() = find<ContainerFixture>(
            byXpath("//div[contains(@myvisibleactions, 'Me')]//div[@myaction.key='action.Stop.text']")
        )

    val runnerTabDebugger
        get() = find<ContainerFixture>(
            byXpath("//div[@accessiblename='Debugger' and @accessiblename.key='xdebugger.attach.popup.selectDebugger.title xdebugger.debugger.tab.title' and @class='SingleHeightLabel']"),
            Duration.ofSeconds(30)
        )

    val debuggerConnected
        get() = find<ContainerFixture>(byXpath("//div[@class='XDebuggerTree']"))

    val xDebuggerFramesList
        get() = find<ContainerFixture>(byXpath("//div[@class='XDebuggerFramesList']"))

    @JvmOverloads
    fun dumbAware(timeout: Duration = Duration.ofMinutes(5), function: () -> Unit) {
        step("Wait for smart mode") {
            waitFor(duration = timeout, interval = Duration.ofSeconds(5)) {
                runCatching { isDumbMode().not() }.getOrDefault(false)
            }
            function()
            step("..wait for smart mode again") {
                waitFor(duration = timeout, interval = Duration.ofSeconds(5)) {
                    isDumbMode().not()
                }
            }
        }
    }

    private fun isDumbMode(): Boolean {
        return callJs(
            """
            const frameHelper = com.intellij.openapi.wm.impl.ProjectFrameHelper.getFrameHelper(component)
            if (frameHelper) {
                const project = frameHelper.getProject()
                project ? com.intellij.openapi.project.DumbService.isDumb(project) : true
            } else { 
                true 
            }
        """, true
        )
    }

    fun closeTipOfTheDay() {
        step("Close Tip of the Day if it appears") {
            waitFor(Duration.ofSeconds(20)) {
                remoteRobot.findAll(
                    DialogFixture::class.java,
                    byXpath("//div[@class='MyDialog'][.//div[@text='Running startup activities...']]")
                ).isEmpty()
            }
            val idea: IdeaFrame = remoteRobot.find(IdeaFrame::class.java, Duration.ofSeconds(10))
            idea.dumbAware {
                try {
                    idea.find(DialogFixture::class.java, DialogFixture.byTitle("Tip of the Day"))
                        .button("Close")
                        .click()
                } catch (ignore: Throwable) {
                }
            }
        }
    }
}


fun RemoteRobot.editorTabs(function: EditorTabs.() -> Unit) {
    find<EditorTabs>(timeout = Duration.ofSeconds(10)).apply(function)
}

@DefaultXpath("EditorTabs type", "//div[@class='EditorTabs']")
class EditorTabs(remoteRobot: RemoteRobot, remoteComponent: RemoteComponent) :
    CommonContainerFixture(remoteRobot, remoteComponent) {

    fun isFileOpened(fileName: String) : Boolean {
        return find<ContainerFixture>(
            byXpath("//div[@visible_text='$fileName' and @class='SimpleColoredComponent']"),
            Duration.ofSeconds(10)
        ).isShowing
    }
}

fun RemoteRobot.fileIntention(function: FileLevelIntentionComponent.() -> Unit) {
    find<FileLevelIntentionComponent>(timeout = Duration.ofSeconds(60)).apply(function)
}

@DefaultXpath("FileLevelIntentionComponent type", "//div[@class='FileLevelIntentionComponent']")
class FileLevelIntentionComponent(remoteRobot: RemoteRobot, remoteComponent: RemoteComponent) :
    CommonContainerFixture(remoteRobot, remoteComponent) {

    val setUpPoetry
        get() = find<ContainerFixture>(
            byXpath("//div[@accessiblename.key='sdk.set.up.poetry.environment']"),
            Duration.ofSeconds(20)
        )
}
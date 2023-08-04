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
    find<IdeaFrame>(timeout = Duration.ofSeconds(120)).apply(function)
}

@FixtureName("Idea frame")
@DefaultXpath("IdeFrameImpl type", "//div[@class='IdeFrameImpl']")
class IdeaFrame(remoteRobot: RemoteRobot, remoteComponent: RemoteComponent) :
    CommonContainerFixture(remoteRobot, remoteComponent) {

    val enableMirrord
        get() = find<ContainerFixture>(byXpath("//div[@myicon='mirrord.svg']"), Duration.ofSeconds(30))

    val mirrordDropdownButton
        get() = find<ContainerFixture>(
            byXpath("//div[@text='mirrord' and @class='ComboBoxButton']"),
            Duration.ofSeconds(30)
        )

    val usageBanner
        get() = find<ContainerFixture>(
            byXpath("//div[@class='MyDialog' and @title='How to use mirrord']"),
            Duration.ofSeconds(30)
        )

    val git
        get() = find<ContainerFixture>(byXpath("//div[@visible_text='Git:' and @class='MyLabel']"), Duration.ofSeconds(30))

    val projectViewTree
        get() = find<ContainerFixture>(
            byXpath("ProjectViewTree", "//div[@class='ProjectViewTree']"),
            Duration.ofSeconds(60)
        )

    val mirrordDropdownMenu
        get() = find<ContainerFixture>(
            byXpath("//div[@class='MyList' and (@visible_text='Disabled || Select Active Config || Configuration || Settings' or @visible_text='Disabled || Settings || Configuration')]"),
            Duration.ofSeconds(30)
        )

    val startDebugging
        get() = find<ContainerFixture>(
            byXpath("//div[@class='ActionButton' and @myaction='Debug (Debug selected configuration)']")
        )

    val stopDebugging
        get() = findAll<ContainerFixture>(
            byXpath("//div[@class='ActionButton' and @myaction='Stop (Stop process)']")
        ).first()

    val runnerTabDebugger
        get() = find<ContainerFixture>(
            byXpath("//div[@class='SimpleColoredComponent' and @visible_text='Debugger']"),
            Duration.ofSeconds(30)
        )

    val debuggerConnected
        get() = find<ContainerFixture>(byXpath("//div[@class='XDebuggerTree']"))

    val xDebuggerFramesList
        get() = find<ContainerFixture>(byXpath("//div[@class='XDebuggerFramesList']"))

    // dumb and smart mode refer to the state of the IDE when it is indexing and not indexing respectively
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
        """,
            true
        )
    }
}

fun RemoteRobot.editorTabs(function: EditorTabs.() -> Unit) {
    find<EditorTabs>(timeout = Duration.ofSeconds(60)).apply(function)
}

// represents the open tabs in the editor
@DefaultXpath("EditorTabs type", "//div[@class='EditorTabs']")
class EditorTabs(remoteRobot: RemoteRobot, remoteComponent: RemoteComponent) :
    CommonContainerFixture(remoteRobot, remoteComponent) {

    fun isFileOpened(fileName: String): Boolean {
        return find<ContainerFixture>(
            byXpath("//div[@visible_text='$fileName' and @class='SimpleColoredComponent']"),
            Duration.ofSeconds(10)
        ).isShowing
    }
}

fun RemoteRobot.fileIntention(function: FileLevelIntentionComponent.() -> Unit) {
    find<FileLevelIntentionComponent>(timeout = Duration.ofSeconds(60)).apply(function)
}

// blue hover box that appears when in the text editor asking for poetry setup
@DefaultXpath("FileLevelIntentionComponent type", "//div[@class='FileLevelIntentionComponent']")
class FileLevelIntentionComponent(remoteRobot: RemoteRobot, remoteComponent: RemoteComponent) :
    CommonContainerFixture(remoteRobot, remoteComponent) {

    val setUpPoetry
        get() = find<ContainerFixture>(
            byXpath("//div[@accessiblename.key='sdk.set.up.poetry.environment']"),
            Duration.ofSeconds(20)
        )
}

fun RemoteRobot.statusBar(function: StatusBar.() -> Unit) {
    find<StatusBar>(timeout = Duration.ofSeconds(60)).apply(function)
}

// represents the status bar at the bottom of the IDE, showing tasks like indexing
@DefaultXpath("IdeStatusBarImpl type", "//div[@class='IdeStatusBarImpl']")
class StatusBar(remoteRobot: RemoteRobot, remoteComponent: RemoteComponent) :
    CommonContainerFixture(remoteRobot, remoteComponent) {

    fun isProgressBarEmpty(): Boolean = step("Is progress bar showing") {
        return@step remoteRobot.findAll<ComponentFixture>(
            byXpath("//div[@class='JProgressBar']")
        ).isEmpty()
    }
}

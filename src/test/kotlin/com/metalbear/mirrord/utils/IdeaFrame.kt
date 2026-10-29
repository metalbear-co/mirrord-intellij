package com.metalbear.mirrord.utils

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.data.RemoteComponent
import com.intellij.remoterobot.fixtures.*
import com.intellij.remoterobot.search.locators.byXpath
import com.intellij.remoterobot.stepsProcessing.step
import com.intellij.remoterobot.utils.component
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
        get() = find<ContainerFixture>(byXpath("//div[@myicon='mirrord_disabled.svg']"), Duration.ofSeconds(30))

    val mirrordDropdownButton
        get() = find<ContainerFixture>(
            byXpath("//div[@visible_text='mirrord' and @class='ActionButtonWithText']"),
            Duration.ofSeconds(60)
        )

    val usageBanner
        get() = find<ContainerFixture>(
            byXpath("//div[@class='MyDialog' and @title='How to use mirrord']"),
            Duration.ofSeconds(30)
        )

    /**
     * The open dropdown, or null when it is not open.
     *
     * Deliberately does not wait, and never throws. The previous form waited 60s and
     * ended in `list!!`, so a menu that never opened raised an NPE -- which aborted the
     * caller's own `waitFor` instead of letting it retry. Waiting here is also useless:
     * the menu only appears in response to a click, so the caller must re-click, not
     * wait harder. See openMirrordDropdown.
     */
    val mirrordDropdownMenu: ContainerFixture?
        get() = findAll<ContainerFixture>(byXpath("//div[@class='MyList']"))
            .firstOrNull { it.hasText("mirrord for Teams") }

    /**
     * Clicks the mirrord button until the dropdown actually opens.
     *
     * A single click is sometimes swallowed -- the button exists and is enabled, but the
     * action does not register -- and then nothing reopens it.
     */
    fun openMirrordDropdown(timeout: Duration = Duration.ofSeconds(90)): ContainerFixture {
        waitFor(timeout, Duration.ofSeconds(3)) {
            if (mirrordDropdownMenu != null) return@waitFor true
            runCatching { mirrordDropdownButton.click() }
            mirrordDropdownMenu != null
        }
        return mirrordDropdownMenu ?: error("mirrord dropdown did not open within $timeout")
    }

    val startDebugging
        get() = find<ContainerFixture>(
            byXpath("//div[@class='ActionButton' and @myaction='Debug (Debug selected configuration)']")
        )

    val stopDebugging
        get() = findAll<ContainerFixture>(
            byXpath("//div[@class='ActionButton' and @myaction='Stop (Stop the process)']")
        ).first()

    // These two `find`s THROW on timeout, and that exception escapes the enclosing
    // `waitFor(ofSeconds(60))` instead of being retried -- so the real budget was 30s,
    // not the 60s the call site appears to give. Measured on a failing CI run: the wait
    // ended after 31.6s (elapsed 166881ms -> 198487ms).
    //
    // The debugger attaching and the app binding its port both cross the cluster through
    // mirrord, which is the slowest part of the run. 30s is not a generous budget for
    // that on a loaded CI runner.
    val debuggerConnected
        get() = find<ContainerFixture>(
            byXpath("//div[@class='EditorComponentImpl' and contains(@visible_text, 'Connected to pydev debugger')]"),
            Duration.ofSeconds(120)
        )

    val appRunning
        get() = find<ContainerFixture>(
            byXpath("//div[@class='EditorComponentImpl' and contains(@visible_text, 'Press CTRL+C to quit')]"),
            Duration.ofSeconds(120)
        )

    val xDebuggerFramesList
        get() = find<ContainerFixture>(
            byXpath(
                "//div[@class='XDebuggerFramesList' and contains(@visible_text, 'get') and contains(@visible_text, 'app.py') and contains(@visible_text, '8')]"
            ),
            Duration.ofSeconds(30)
        )

    fun ContainerFixture.isComponentEnabled(): Boolean {
        return callJs(
            """
            component.isEnabled()
        """,
            true
        )
    }

    // dumb and smart mode refer to the state of the IDE when it is indexing and not indexing respectively
    @JvmOverloads
    fun dumbAware(
        timeout: Duration = Duration.ofMinutes(5),
        waitAfter: Boolean = true,
        function: () -> Unit
    ) {
        step("Wait for smart mode") {
            waitFor(duration = timeout, interval = Duration.ofSeconds(5)) {
                runCatching { isDumbMode().not() }.getOrDefault(false)
            }
            function()
            if (waitAfter) {
                step("...wait for smart mode again") {
                    waitFor(duration = timeout, interval = Duration.ofSeconds(5)) {
                        isDumbMode().not()
                    }
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

fun RemoteRobot.pythonSetupPrompt(function: PythonSetupPrompt.() -> Unit) {
    find<PythonSetupPrompt>(timeout = Duration.ofSeconds(60)).apply(function)
}

// inline Python environment setup prompt above the editor
@DefaultXpath(
    "Python setup prompt",
    "//div[@class='ActionLink' and @text='Set up Poetry environment']"
)
class PythonSetupPrompt(remoteRobot: RemoteRobot, remoteComponent: RemoteComponent) :
    CommonContainerFixture(remoteRobot, remoteComponent)

fun RemoteRobot.statusBar(function: StatusBar.() -> Unit) {
    find<StatusBar>(timeout = Duration.ofSeconds(60)).apply(function)
}

// represents the status bar at the bottom of the IDE, showing tasks like indexing
@DefaultXpath("IdeStatusBarImpl type", "//div[@class='IdeStatusBarImpl']")
class StatusBar(remoteRobot: RemoteRobot, remoteComponent: RemoteComponent) :
    CommonContainerFixture(remoteRobot, remoteComponent) {

    val progressIcon
        get() = find<ContainerFixture>(
            byXpath("//div[@class='AsyncProcessIcon']"),
            Duration.ofSeconds(30)
        )

    fun waitForProgressFinished(timeout: Duration) {
        waitFor(duration = timeout, errorMessage = "There are still some active background processes") {
            val found = find<ContainerFixture>(
                byXpath("//div[@class='InlineProgressPanel']")
            ).findAllText().map { it.text }
            found.isEmpty()
        }
    }
}

fun RemoteRobot.openFile(path: String) {
    val ideaFrame = component("//div[@class='IdeFrameImpl']")
    ideaFrame.runJs(
        """
            importPackage(com.intellij.openapi.fileEditor)
            importPackage(com.intellij.openapi.vfs)
            importPackage(com.intellij.openapi.wm.impl)
            importClass(com.intellij.openapi.application.ApplicationManager)
            
            const path = '$path'
            const frameHelper = ProjectFrameHelper.getFrameHelper(component)
            if (frameHelper) {
                const project = frameHelper.getProject()
                const projectPath = project.getBasePath()
                const file = LocalFileSystem.getInstance().findFileByPath(projectPath + '/' + path)
                const openFileFunction = new Runnable({
                    run: function() {
                        FileEditorManager.getInstance(project).openTextEditor(
                            new OpenFileDescriptor(
                                project,
                                file
                            ), true
                        )
                    }
                })
                ApplicationManager.getApplication().invokeLater(openFileFunction)
            }
        """,
        true
    )
}

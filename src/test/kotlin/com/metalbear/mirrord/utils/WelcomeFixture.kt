// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package com.metalbear.mirrord.utils

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.data.RemoteComponent
import com.intellij.remoterobot.fixtures.*
import java.time.Duration

// Note: some implementation is taken from the example provided at https://github.com/JetBrains/intellij-ui-test-robot

fun RemoteRobot.welcomeFrame(function: WelcomeFrame.() -> Unit) {
    find(WelcomeFrame::class.java, Duration.ofSeconds(10)).apply(function)
}

@FixtureName("Welcome Frame")
@DefaultXpath("type", "//div[@class='FlatWelcomeFrame']")
class WelcomeFrame(remoteRobot: RemoteRobot, remoteComponent: RemoteComponent) :
    CommonContainerFixture(remoteRobot, remoteComponent) {
    fun openProject(absolutePath: String) {
        remoteRobot.runJs(
            """
            importClass(com.intellij.openapi.application.ApplicationManager)
            importClass(com.intellij.ide.impl.OpenProjectTask)
           
            const projectManager = com.intellij.openapi.project.ex.ProjectManagerEx.getInstanceEx()
            let task 
            try { 
                task = OpenProjectTask.build()
            } catch(e) {
                task = OpenProjectTask.newProject()
            }
            const path = new java.io.File("$absolutePath").toPath()
           
            const openProjectFunction = new Runnable({
                run: function() {
                    projectManager.openProject(path, task)
                }
            })
           
            ApplicationManager.getApplication().invokeLater(openProjectFunction)
        """
        )
    }
}
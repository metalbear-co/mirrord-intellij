// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package com.metalbear.mirrord.utils

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.data.RemoteComponent
import com.intellij.remoterobot.fixtures.CommonContainerFixture
import com.intellij.remoterobot.fixtures.ContainerFixture
import com.intellij.remoterobot.fixtures.FixtureName
import com.intellij.remoterobot.search.locators.byXpath
import com.intellij.remoterobot.stepsProcessing.step
import java.time.Duration

// Note: some implementation is taken from the example provided at https://github.com/JetBrains/intellij-ui-test-robot

fun ContainerFixture.dialog(
    title: String,
    timeout: Duration = Duration.ofSeconds(30),
    function: DialogFixture.() -> Unit = {}
): DialogFixture = step("Search for dialog with title $title") {
    find<DialogFixture>(DialogFixture.byTitle(title), timeout).apply(function)
}

@FixtureName("Dialog")
class DialogFixture(
    remoteRobot: RemoteRobot,
    remoteComponent: RemoteComponent
) : CommonContainerFixture(remoteRobot, remoteComponent) {

    companion object {
        @JvmStatic
        fun byTitle(title: String) = byXpath("title $title", "//div[@title='$title' and @class='MyDialog']")
    }
}

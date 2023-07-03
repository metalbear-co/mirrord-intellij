package com.metalbear.mirrord.utils

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.utils.Keyboard
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.ParameterContext
import org.junit.jupiter.api.extension.ParameterResolver

class RemoteRobotExtension : ParameterResolver {
    private val url: String = System.getProperty("remote-robot-url") ?: "http://127.0.0.1:8082"
    private val remoteRobot: RemoteRobot = RemoteRobot(url)
    private val keyboard = Keyboard(remoteRobot)

    override fun supportsParameter(parameterContext: ParameterContext?, extensionContext: ExtensionContext?): Boolean {
        return parameterContext?.parameter?.type?.equals(RemoteRobot::class.java) ?: false
    }

    override fun resolveParameter(parameterContext: ParameterContext?, extensionContext: ExtensionContext?): Any {
        return remoteRobot
    }
}

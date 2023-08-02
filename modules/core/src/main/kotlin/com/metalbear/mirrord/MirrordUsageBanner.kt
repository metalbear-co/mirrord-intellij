package com.metalbear.mirrord

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.ImageUtil
import com.intellij.util.ui.JBUI
import icons.MirrordIcons
import java.awt.Dimension
import java.awt.event.ActionEvent
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.*
import javax.swing.text.DefaultStyledDocument
import javax.swing.text.StyleConstants
import javax.swing.text.StyleContext


const val BANNER_TEXT =
    """To enable mirrord, simply click the icon on the run toolbar.
When mirrord is enabled, running or debugging your project will use the context of the Kubernetes cluster specified in your kubeconfig.
The dropdown menu next to the icon provides shortcuts for working with mirrord configuration files.
"""

class MirrordUsageBanner : StartupActivity, StartupActivity.DumbAware {

    class BannerDialog : DialogWrapper(true) {
        init {
            title = "How to use mirrord"
            init()
        }

        override fun createCenterPanel(): JComponent {
            val styleContext = StyleContext()
            val document = DefaultStyledDocument()

            val style = styleContext.getStyle(StyleContext.DEFAULT_STYLE)
            StyleConstants.setAlignment(style, StyleConstants.ALIGN_LEFT)
            StyleConstants.setFontSize(style, 18)
            StyleConstants.setSpaceAbove(style, 6f)
            StyleConstants.setSpaceBelow(style, 6f)
            document.insertString(document.length, BANNER_TEXT, style)

            val textPane = JTextPane(document).apply { isEditable = false }

            val dialogPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                border = JBUI.Borders.empty(10, 5)
                add(JLabel(MirrordIcons.usage).apply { alignmentX = JLabel.CENTER_ALIGNMENT })
                add(Box.createRigidArea(Dimension(0, 20)))
                add(JBScrollPane(textPane))
            }

            return dialogPanel
        }

        override fun createActions(): Array<Action> {
            return arrayOf(
                DialogWrapperExitAction("Close", CLOSE_EXIT_CODE).apply {
                    putValue(DEFAULT_ACTION, true)
                },
                object : DialogWrapperExitAction("Don't Show Again", CLOSE_EXIT_CODE) {
                    override fun doAction(e: ActionEvent?) {
                        MirrordSettingsState.instance.mirrordState.showUsageBanner = false
                        super.doAction(e)
                    }
                }
            )
        }
    }

    override fun runActivity(project: Project) {
        if (!MirrordSettingsState.instance.mirrordState.showUsageBanner) {
            return
        }

        ApplicationManager.getApplication().invokeLater {
            BannerDialog().show()
        }
    }
}

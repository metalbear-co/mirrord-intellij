package com.metalbear.mirrord

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import javax.swing.Action
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextPane
import javax.swing.text.DefaultStyledDocument
import javax.swing.text.StyleConstants
import javax.swing.text.StyleContext

class MirrordWindowsUnsupportedDialog private constructor(
    private val bodyText: String,
    private val link: Pair<String, String>?,
) : DialogWrapper(true) {
    init {
        title = "mirrord: Windows-native support unavailable"
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
        document.insertString(document.length, bodyText, style)

        val textPane = JTextPane(document).apply { isEditable = false }

        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(10, 5)
            add(JBScrollPane(textPane))
            link?.let { (label, url) ->
                add(Box.createRigidArea(Dimension(0, 10)))
                add(
                    HyperlinkLabel(label).apply {
                        setHyperlinkTarget(url)
                        alignmentX = 0f
                    },
                )
            }
        }
    }

    override fun createActions(): Array<Action> =
        arrayOf(
            DialogWrapperExitAction("Close", CLOSE_EXIT_CODE).apply {
                putValue(DEFAULT_ACTION, true)
            },
        )

    companion object {
        private const val ARCH_ISSUE_URL = "https://github.com/metalbear-co/mirrord/issues/4162"

        @Volatile
        private var archShownThisSession = false

        @Volatile
        private var versionShownThisSession = false

        fun showVersionUnsupportedOnce(
            required: String,
            found: String,
            path: String? = null,
        ) {
            if (versionShownThisSession) return
            versionShownThisSession = true
            val body = buildVersionUnsupportedBody(required, found, path)
            show(body, null)
        }

        fun buildVersionUnsupportedBody(
            required: String,
            found: String,
            path: String?,
        ): String {
            val core =
                "Windows-native mirrord requires binary version $required or newer.\n" +
                    "Found: $found\n\n" +
                    "Non-WSL run/debug configurations will not work until the binary is upgraded. " +
                    "WSL-based configurations continue to work.\n\n" +
                    "Enable auto-update in mirrord settings, or pin a version ≥ $required."
            return if (path != null) "$core\n\nmirrord path: $path" else core
        }

        fun showArchUnsupportedOnce(arch: String) {
            if (archShownThisSession) return
            archShownThisSession = true
            val body =
                "mirrord does not currently provide a Windows $arch build.\n\n" +
                    "If you require $arch support, please upvote or comment on the issue below " +
                    "so we can gauge interest."
            show(body, "Upvote $arch support on GitHub" to ARCH_ISSUE_URL)
        }

        private fun show(
            bodyText: String,
            link: Pair<String, String>?,
        ) {
            ApplicationManager.getApplication().invokeLater {
                MirrordWindowsUnsupportedDialog(bodyText, link).show()
            }
        }
    }
}

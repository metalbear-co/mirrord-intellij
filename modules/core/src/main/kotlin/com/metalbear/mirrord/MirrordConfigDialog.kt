package com.metalbear.mirrord

import com.intellij.openapi.ui.DialogBuilder
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.event.*
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

private const val PLACEHOLDER = "Filter configuration files..."

/**
 * Dialog for selecting configuration files.
 */
class MirrordConfigDialog(private val title: String, private val options: List<String>) {
    class Selection(val option: String?)

    /**
     * Shows selection dialog and returns user's selection.
     * Dialog contains a search field.
     * @return null if user cancelled
     */
    fun show(): Selection? {
        val jbOptions = JBList(options).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
        }

        val searchField = JTextField().apply {
            val field = this

            // Add an informative placeholder.
            val previousForeground = foreground
            text = PLACEHOLDER
            foreground = JBColor.GRAY
            addFocusListener(object : FocusListener {
                override fun focusGained(e: FocusEvent) {
                    if (field.text.equals(PLACEHOLDER)) {
                        field.text = ""
                        field.foreground = previousForeground
                    }
                }

                override fun focusLost(e: FocusEvent) {
                    if (field.text.isEmpty()) {
                        field.foreground = JBColor.GRAY
                        field.text = PLACEHOLDER
                    }
                }
            })

            document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) = updateList()
                override fun removeUpdate(e: DocumentEvent) = updateList()
                override fun changedUpdate(e: DocumentEvent) = updateList()

                private fun updateList() {
                    val searchTerm = field.text
                    if (!searchTerm.equals(PLACEHOLDER)) {
                        jbOptions.setListData(options.filter { it.contains(searchTerm) }.toTypedArray())
                    }
                }
            })
        }

        val result = DialogBuilder().apply {
            setCenterPanel(createSelectionDialog(jbOptions, searchField))
            setTitle(title)
            setPreferredFocusComponent(searchField)
        }.show()

        if (result == DialogWrapper.OK_EXIT_CODE) {
            return Selection(jbOptions.selectedValue)
        }

        return null
    }

    private fun createSelectionDialog(items: JBList<String>, searchField: JTextField): JPanel =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(10, 5)
            add(searchField.apply {
                alignmentX = JBScrollPane.LEFT_ALIGNMENT
                preferredSize = Dimension(250, 30)
                size = Dimension(250, 30)
            })
            add(Box.createRigidArea(Dimension(0, 10)))
            add(JBScrollPane(items.apply {
                minimumSize = Dimension(250, 350)
            }).apply {
                alignmentX = JBScrollPane.LEFT_ALIGNMENT
            })
        }
}

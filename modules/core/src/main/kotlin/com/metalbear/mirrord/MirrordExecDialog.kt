package com.metalbear.mirrord

import com.intellij.openapi.ui.DialogBuilder
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.event.KeyEvent
import java.awt.event.KeyListener
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener


object MirrordExecDialog {
    private const val dialogHeading: String = "mirrord"
    private const val targetLabel = "Select Target"

    /**
     * Label that's used to select targetless mode
     */
    const val targetlessTargetName = "No Target (\"targetless\")"

    /**
     * Shows a target selection dialog.
     *
     * @return a target selected from the given list, targetlessTargetName constant if user selected targetless, null if the user cancelled
     */
    fun selectTargetDialog(podTargets: List<String>): String? {
        val targets = podTargets.sorted().toMutableList()
        MirrordSettingsState.instance.mirrordState.lastChosenTarget?.let {
            val idx = targets.indexOf(it)
            if (idx != -1) {
                targets.removeAt(idx)
                targets.add(0, it)
            }
        }
        targets.add(targetlessTargetName)

        val jbTargets = targets.asJBList()
        val searchField = JTextField()
        searchField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = updateList()
            override fun removeUpdate(e: DocumentEvent) = updateList()
            override fun changedUpdate(e: DocumentEvent) = updateList()

            private fun updateList() {
                val searchTerm = searchField.text
                val filteredTargets = targets
                        .dropLast(1) // targetless is always last
                        .filter { it.contains(searchTerm, true) }
                        .sorted() + targetlessTargetName
                jbTargets.setListData(filteredTargets.toTypedArray())
            }

        })

        // Add focus logic then we can change back and forth from search field
        // to target selection using tab/shift+tab
        searchField.addKeyListener(object : KeyListener {
            override fun keyTyped(p0: KeyEvent) {
            }

            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_TAB) {
                    if (e.modifiersEx > 0) {
                        searchField.transferFocusBackward()
                    } else {
                        searchField.transferFocus()
                    }
                    e.consume()
                }
            }

            override fun keyReleased(p0: KeyEvent) {
            }
        })
        val result = DialogBuilder().apply {
            setCenterPanel(createSelectionDialog(jbTargets, searchField))
            setTitle(dialogHeading)
        }.show()
        if (result == DialogWrapper.OK_EXIT_CODE) {
            if (jbTargets.isSelectionEmpty) {
                // The user did not select any target, and clicked ok.
                return targetlessTargetName
            }
            val selectedValue = jbTargets.selectedValue
            MirrordSettingsState.instance.mirrordState.lastChosenTarget = selectedValue
            return selectedValue
        }
        // The user clicked cancel, or closed the dialog.
        return null
    }

    private fun List<String>.asJBList() = JBList(this).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
    }

    private fun createSelectionDialog(items: JBList<String>, searchField: JTextField): JPanel =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                border = JBUI.Borders.empty(10, 5)
                add(JLabel(targetLabel).apply {
                    alignmentX = JLabel.LEFT_ALIGNMENT
                })
                add(Box.createRigidArea(Dimension(0, 10)))
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

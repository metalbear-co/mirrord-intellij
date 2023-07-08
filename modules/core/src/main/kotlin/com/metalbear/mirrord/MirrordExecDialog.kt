package com.metalbear.mirrord

import com.intellij.openapi.ui.DialogBuilder
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.event.*
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

object MirrordExecDialog {
    private const val dialogHeading: String = "mirrord"
    private const val targetLabel = "Select Target"
    private const val searchPlaceHolder = "Filter targets..."

    /**
     * Label that's used to select targetless mode
     */
    const val targetlessTargetName = "No Target (\"targetless\")"

    /**
     * Manages the state of targets list in the dialog. Keeps all the filters in one place.
     */
    private class TargetsState(private var availableTargets: List<String>) {
        /**
         * Whether to show pods.
         */
        var pods = MirrordSettingsState.instance.mirrordState.showPodsInSelection ?: true

        /**
         * Whether to show deployments.
         */
        var deployments = MirrordSettingsState.instance.mirrordState.showDeploymentsInSelection ?: true

        /**
         * Show only targets containing this phrase.
         */
        var searchPhrase = ""

        /**
         * Filtered and sorted targets, targetless option at the bottom, last chosen target at the top.
         */
        val targets: List<String>
            get() {
                return this.availableTargets
                    .filter { this.pods && (it.startsWith("pod/")) || (this.deployments && it.startsWith("deployment/")) }
                    .filter { it.contains(this.searchPhrase) }
                    .toMutableList()
                    .apply {
                        sort()
                        MirrordSettingsState.instance.mirrordState.lastChosenTarget?.let {
                            val idx = this.indexOf(it)
                            if (idx != -1) {
                                this.removeAt(idx)
                                this.add(0, it)
                            }
                        }
                        add(targetlessTargetName)
                    }
                    .toList()
            }
    }

    /**
     * Shows a target selection dialog.
     *
     * @return a target selected from the given list, targetlessTargetName constant if user selected targetless, null if the user cancelled
     */
    fun selectTargetDialog(availableTargets: List<String>): String? {
        val targetsState = TargetsState(availableTargets)

        val jbTargets = (targetsState.targets).asJBList()
        val searchField = JTextField().apply {
            val field = this

            // Add an informative placeholder.
            val previousForeground = foreground
            text = searchPlaceHolder
            foreground = JBColor.GRAY
            addFocusListener(object : FocusListener {
                override fun focusGained(e: FocusEvent) {
                    if (field.text.equals(searchPlaceHolder)) {
                        field.text = ""
                        field.foreground = previousForeground
                    }
                }

                override fun focusLost(e: FocusEvent) {
                    if (field.text.isEmpty()) {
                        field.foreground = JBColor.GRAY
                        field.text = searchPlaceHolder
                    }
                }
            })

            // Add filtering logic on search field update.
            document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) = updateList()
                override fun removeUpdate(e: DocumentEvent) = updateList()
                override fun changedUpdate(e: DocumentEvent) = updateList()

                private fun updateList() {
                    val searchTerm = field.text
                    if (!searchTerm.equals(searchPlaceHolder)) {
                        targetsState.searchPhrase = searchTerm
                        jbTargets.setListData(targetsState.targets.toTypedArray())
                    }
                }
            })

            // Add focus logic so that the user can change back and forth from search field
            // to target selection using tab/shift+tab.
            addKeyListener(object : KeyListener {
                override fun keyTyped(p0: KeyEvent) {
                }

                override fun keyPressed(e: KeyEvent) {
                    if (e.keyCode == KeyEvent.VK_TAB) {
                        if (e.modifiersEx > 0) {
                            field.transferFocusBackward()
                        } else {
                            field.transferFocus()
                        }
                        e.consume()
                    }
                }

                override fun keyReleased(p0: KeyEvent) {
                }
            })
        }
        val filterHelpers = listOf(
            JBCheckBox("Pods", targetsState.pods).apply {
                this.addActionListener {
                    targetsState.pods = this.isSelected
                    jbTargets.setListData(targetsState.targets.toTypedArray())
                }
            },
            JBCheckBox("Deployments", targetsState.deployments).apply {
                this.addActionListener {
                    targetsState.deployments = this.isSelected
                    jbTargets.setListData(targetsState.targets.toTypedArray())
                }
            }
        )
        val result = DialogBuilder().apply {
            setCenterPanel(createSelectionDialog(jbTargets, searchField, filterHelpers))
            setTitle(dialogHeading)
            setPreferredFocusComponent(searchField)
        }.show()

        if (result == DialogWrapper.OK_EXIT_CODE) {
            MirrordSettingsState.instance.mirrordState.showPodsInSelection = targetsState.pods
            MirrordSettingsState.instance.mirrordState.showDeploymentsInSelection = targetsState.deployments

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

    private fun createSelectionDialog(items: JBList<String>, searchField: JTextField, filterHelpers: List<JComponent>): JPanel =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(10, 5)
            add(
                JLabel(targetLabel).apply {
                    alignmentX = JLabel.LEFT_ALIGNMENT
                }
            )
            add(Box.createRigidArea(Dimension(0, 10)))
            add(
                JBBox.createHorizontalBox().apply {
                    filterHelpers.forEach {
                        this.add(it)
                        this.add(Box.createRigidArea(Dimension(10, 0)))
                    }
                    alignmentX = JBBox.LEFT_ALIGNMENT
                }
            )
            add(Box.createRigidArea(Dimension(0, 10)))
            add(
                searchField.apply {
                    alignmentX = JBScrollPane.LEFT_ALIGNMENT
                    preferredSize = Dimension(250, 30)
                    size = Dimension(250, 30)
                }
            )
            add(Box.createRigidArea(Dimension(0, 10)))
            add(
                JBScrollPane(
                    items.apply {
                        minimumSize = Dimension(250, 350)
                    }
                ).apply {
                    alignmentX = JBScrollPane.LEFT_ALIGNMENT
                }
            )
        }
}

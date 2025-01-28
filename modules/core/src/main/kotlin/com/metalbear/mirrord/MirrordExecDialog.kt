package com.metalbear.mirrord

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.event.*
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class MirrordExecDialog(project: Project, private val getTargets: (String?) -> MirrordApi.MirrordLsOutput) : DialogWrapper(project, true) {
    companion object {
        const val TARGETLESS_SELECTION_VALUE = "No Target (\"targeteless\")"
        private const val TARGET_FILTER_PLACEHOLDER = "Filter targets..."
    }

    private var fetched: MirrordApi.MirrordLsOutput = getTargets(null)

    private val targetOptions: JBList<String> = JBList(emptyList<String>()).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        minimumSize = Dimension(250, 350)
    }

    private val namespaceOptions: ComboBox<String> = ComboBox()

    /**
     * Whether to show pods.
     */
    private val showPods: JBCheckBox = JBCheckBox("Pods", MirrordSettingsState.instance.mirrordState.showPodsInSelection ?: true).apply {
        this.addActionListener {
            refresh()
        }
    }

    /**
     * Whether to show deployments.
     */
    private val showDeployments: JBCheckBox = JBCheckBox("Deployments", MirrordSettingsState.instance.mirrordState.showDeploymentsInSelection ?: true).apply {
        this.addActionListener {
            refresh()
        }
    }

    /**
     * Whether to show rollouts.
     */
    private val showRollouts: JBCheckBox = JBCheckBox("Rollouts", MirrordSettingsState.instance.mirrordState.showRolloutsInSelection ?: true).apply {
        this.addActionListener {
            refresh()
        }
    }

    private val targetFilter = JTextField().apply {
        val field = this

        // Add an informative placeholder.
        val previousForeground = foreground
        text = TARGET_FILTER_PLACEHOLDER
        foreground = JBColor.GRAY
        addFocusListener(object : FocusListener {
            override fun focusGained(e: FocusEvent) {
                if (field.text.equals(TARGET_FILTER_PLACEHOLDER)) {
                    field.text = ""
                    field.foreground = previousForeground
                }
            }

            override fun focusLost(e: FocusEvent) {
                if (field.text.isEmpty()) {
                    field.foreground = JBColor.GRAY
                    field.text = TARGET_FILTER_PLACEHOLDER
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
                if (!searchTerm.equals(TARGET_FILTER_PLACEHOLDER)) {
                    refresh()
                }
            }
        })

        // Add focus logic so that the user can change back and forth from search field
        // to target selection using tab/shift+tab.
        addKeyListener(object : KeyListener {
            override fun keyTyped(p0: KeyEvent) {}

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

            override fun keyReleased(p0: KeyEvent) {}
        })

        alignmentX = JBScrollPane.LEFT_ALIGNMENT
        maximumSize = Dimension(10000, 30)
    }

    private val selectTargetLabel = JLabel("Select Target").apply {
        alignmentX = JLabel.LEFT_ALIGNMENT
        font = JBFont.create(font.deriveFont(Font.BOLD), false)
    }

    private val selectNamespaceLabel = JLabel("Select Namespace").apply {
        alignmentX = JLabel.LEFT_ALIGNMENT
        font = JBFont.create(font.deriveFont(Font.BOLD), false)
    }

    private val verticalSeparator: Component
        get() = Box.createRigidArea(Dimension(0, 10))

    private val horizontalSeparator: Component
        get() = Box.createRigidArea(Dimension(10, 0))

    init {
        title = "mirrord"
        refresh()
        init()
    }

    private fun refresh() {
        val selectableTargets = fetched
            .targets
            .asSequence()
            .filter { it.available }
            .map { it.path }
            .filter {
                (showPods.isSelected && it.startsWith("pod/")) ||
                        (showDeployments.isSelected && it.startsWith("deployment/")) ||
                        (showRollouts.isSelected && it.startsWith("rollout/"))
            }
            .filter { targetFilter.text == TARGET_FILTER_PLACEHOLDER || it.contains(targetFilter.text) }
            .toMutableList()
            .apply {
                MirrordSettingsState.instance.mirrordState.lastChosenTarget?.let {
                    val idx = this.indexOf(it)
                    if (idx != -1) {
                        this.removeAt(idx)
                        this.add(0, it)
                    }
                }
                add(TARGETLESS_SELECTION_VALUE)
            }
            .toTypedArray()
        targetOptions.setListData(selectableTargets)

        namespaceOptions.removeAllItems()
        fetched.namespaces?.forEach { namespaceOptions.addItem(it) }
        fetched.currentNamespace?.let { namespaceOptions.selectedItem = it }
    }

    override fun createCenterPanel(): JComponent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(10, 5)
        preferredSize = Dimension(400, 400)

        if (fetched.currentNamespace != null && fetched.namespaces != null) {
            add(
                JBBox.createHorizontalBox().apply {
                    add(selectNamespaceLabel)
                    add(horizontalSeparator)
                    add(JComboBox<String>().apply {
                        selectedItem = fetched.currentNamespace
                        fetched.namespaces?.forEach( this::addItem )
                    })
                    alignmentX = JBBox.LEFT_ALIGNMENT
                    maximumSize = Dimension(10000, 30)
                }
            )
            add(verticalSeparator)
        }

        add(selectTargetLabel)
        add(verticalSeparator)
        add(
            JBBox.createHorizontalBox().apply {
                add(showPods)
                add(horizontalSeparator)
                add(showDeployments)
                add(horizontalSeparator)
                add(showRollouts)
                alignmentX = JBBox.LEFT_ALIGNMENT
            }
        )
        add(verticalSeparator)
        add(targetFilter)
        add(verticalSeparator)
        add(
            JBScrollPane(targetOptions).apply {
                alignmentX = JBScrollPane.LEFT_ALIGNMENT
            }
        )
    }

    fun showAndGetSelection(): String? {
        return if (showAndGet()) {
            MirrordSettingsState.instance.mirrordState.showPodsInSelection = showPods.isSelected
            MirrordSettingsState.instance.mirrordState.showDeploymentsInSelection = showDeployments.isSelected
            MirrordSettingsState.instance.mirrordState.showRolloutsInSelection = showRollouts.isSelected

            if (targetOptions.isSelectionEmpty) {
                TARGETLESS_SELECTION_VALUE
            } else {
                MirrordSettingsState.instance.mirrordState.lastChosenTarget = targetOptions.selectedValue
                targetOptions.selectedValue
            }
        } else {
            null
        }
    }
}

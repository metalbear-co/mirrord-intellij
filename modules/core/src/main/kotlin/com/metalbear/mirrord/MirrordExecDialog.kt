package com.metalbear.mirrord

import com.intellij.notification.NotificationType
import com.intellij.openapi.components.service
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
import com.metalbear.mirrord.MirrordApi.FoundTarget
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.event.*
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import kotlin.collections.set

/**
 * Target and namespace selection dialog.
 * @param project for getting the MirrordNotifier.
 * @param getTargets function used to fetch targets from the cluster.
 *                   Accepts the name of a namespace where the lookup should be done.
 *                   If no name is given, the default value from the mirrord config should be user.
 */
class MirrordExecDialog(
    private val project: Project,
    private val getTargets: (String?, List<String>) -> MirrordApi.MirrordLsOutput
) : DialogWrapper(project, true) {
    /**
     * Target and namespace selected by the user.
     */
    data class UserSelection(
        /**
         * Path to the target, e.g `pod/my-pod`.
         * null if targetless.
         */
        val target: String?,
        /**
         * Optional target namespace override.
         */
        val namespace: String?
    )

    companion object {
        /**
         * Dummy label we use in the dialog to allow the user for explicitly selecting the targetless mode.
         * There can be no clash with real target labels, because each of those starts with a target type, e.g `pod/`.
         */
        private const val TARGETLESS_SELECTION_LABEL = "No Target (\"targetless\")"

        /**
         * Placeholder value for the target filter.
         */
        private const val TARGET_FILTER_PLACEHOLDER = "Filter targets..."
    }

    /**
     * Store of the targets fetched so far, similar to MirrordApi.MirrordLsOutput.
     * Allows targets to be retrieved by resource type.
     */
    class FetchedTargets(
        /**
         * List of found targets, with resource type as the key.
         * i.e. "pod", "deployment", "rollout".
         */
        var targets: HashMap<String, List<FoundTarget>>,
        /**
         * Namespace where the lookup was done.
         */
        var currentNamespace: String?,
        /**
         * All namespaces available to the user.
         */
        var namespaces: List<String>?
    ) {
        constructor(
            getTargets: (String?, List<String>) -> MirrordApi.MirrordLsOutput,
            currentNamespace: String?,
            fallbackNamespaces: List<String>?,
            pods: Boolean,
            deployments: Boolean,
            rollouts: Boolean
        ) : this(
            HashMap<String, List<FoundTarget>>(),
            currentNamespace,
            fallbackNamespaces
        ) {
            var resourceTypes: MutableList<String> = mutableListOf()
            if (pods) resourceTypes.add("pod")
            if (deployments) resourceTypes.add("deployment")
            if (rollouts) resourceTypes.add("rollout")

            val output = getTargets(currentNamespace, resourceTypes)

            val podsList: MutableList<FoundTarget> = mutableListOf()
            val deploymentsList: MutableList<FoundTarget> = mutableListOf()
            val rolloutsList: MutableList<FoundTarget> = mutableListOf()

            output
                .targets
                .asSequence()
                .forEach {
                    if (it.path.startsWith("pod/")) {
                        podsList += it
                    } else if (it.path.startsWith("deployment/")) {
                        deploymentsList += it
                    } else if (it.path.startsWith("rollout/")) {
                        rolloutsList += it
                    }
                }

            if (podsList.isNotEmpty() || pods) this.targets["pod"] = podsList.toList()
            if (deploymentsList.isNotEmpty() || deployments) this.targets["deployment"] = deploymentsList.toList()
            if (rolloutsList.isNotEmpty() || rollouts) this.targets["rollout"] = rolloutsList.toList()
            this.currentNamespace = output.currentNamespace
            this.namespaces = output.namespaces
        }

        /**
         * Return a list of targets according to resource type filters, either by retrieving them from the stored
         * HashMap (`self.targets`) or, if not present, by executing a call to the mirrord ls command.
         */
        fun getTargetsStoredOrFetch(
            getTargets: (String?, List<String>) -> MirrordApi.MirrordLsOutput,
            pods: Boolean,
            deployments: Boolean,
            rollouts: Boolean
        ): List<FoundTarget> {
            val targets: MutableList<FoundTarget> = mutableListOf()
            val missingResourceTypes: MutableList<String> = mutableListOf()

            // retrieve the targets from storage, or add them to the list of types to be listed
            if (pods) {
                if (this.targets.containsKey("pod")) {
                    targets.addAll(this.targets.get("pod") ?: listOf())
                } else {
                    missingResourceTypes.add("pod")
                }
            }
            if (deployments) {
                if (this.targets.containsKey("deployment")) {
                    targets.addAll(this.targets.get("deployment") ?: listOf())
                } else {
                    missingResourceTypes.add("deployment")
                }
            }
            if (rollouts) {
                if (this.targets.containsKey("rollout")) {
                    targets.addAll(this.targets.get("rollout") ?: listOf())
                } else {
                    missingResourceTypes.add("rollout")
                }
            }

            // if there are types to be listed by the CLI, do so
            if (missingResourceTypes.isNotEmpty()) {
                val output = getTargets(currentNamespace, missingResourceTypes)

                val podsList: MutableList<FoundTarget> = mutableListOf()
                val deploymentsList: MutableList<FoundTarget> = mutableListOf()
                val rolloutsList: MutableList<FoundTarget> = mutableListOf()

                output
                    .targets
                    .asSequence()
                    .forEach {
                        if (it.path.startsWith("pod/")) {
                            podsList += it
                        } else if (it.path.startsWith("deployment/")) {
                            deploymentsList += it
                        } else if (it.path.startsWith("rollout/")) {
                            rolloutsList += it
                        }
                    }

                // depending on which types were requested, return the targets and store them
                if (missingResourceTypes.contains("pod")) {
                    this.targets["pod"] = podsList.toList()
                    targets.addAll(podsList)
                }
                if (missingResourceTypes.contains("deployment")) {
                    this.targets["deployment"] = deploymentsList.toList()
                    targets.addAll(deploymentsList)
                }
                if (missingResourceTypes.contains("rollout")) {
                    this.targets["rollout"] = rolloutsList.toList()
                    targets.addAll(rolloutsList)
                }
            }
            return targets
        }
    }

    /**
     * Targets fetched from the cluster.
     */
    private var fetched: FetchedTargets =
        FetchedTargets(
            getTargets,
            null,
            null,
            MirrordSettingsState.instance.mirrordState.showPodsInSelection ?: true,
            MirrordSettingsState.instance.mirrordState.showDeploymentsInSelection ?: true,
            MirrordSettingsState.instance.mirrordState.showRolloutsInSelection ?: true
        )

    /**
     * Whether we are currently refreshing the widgets with new content.
     *
     * This is set in `refresh` and inspected in the custom `namespaceOptions` data model.
     * Prevents infinite loops and other bugs.
     */
    private var refreshing: Boolean = false

    /**
     * List of targets available in the current namespace.
     */
    private val targetOptions: JBList<String> = JBList(emptyList<String>()).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        minimumSize = Dimension(250, 350)
    }

    /**
     * Dropdown allowing for switching namespaces.
     */
    private val namespaceOptions: ComboBox<String> = ComboBox(object : DefaultComboBoxModel<String>() {
        override fun setSelectedItem(anObject: Any?) {
            super.setSelectedItem(anObject)

            if (refreshing) {
                // If we don't check this, we're going to have problems.
                // `refresh` changes data in this data model, which triggers this function.
                return
            }

            val namespace = anObject as? String? ?: return
            if (fetched.currentNamespace != namespace && fetched.namespaces.orEmpty().contains(namespace)) {
                // namespace changed, recompute fetched targets and fallback to the previous list of namespaces
                val fallbackNamespaces = fetched.namespaces
                fetched = FetchedTargets(
                    getTargets,
                    namespace,
                    fallbackNamespaces,
                    showPods.isSelected,
                    showDeployments.isSelected,
                    showRollouts.isSelected
                )
                refresh()
            }
        }
    })

    /**
     * Checkbox allowing for filtering out pods from the target list.
     */
    private val showPods: JBCheckBox =
        JBCheckBox("Pods", MirrordSettingsState.instance.mirrordState.showPodsInSelection ?: true).apply {
            this.addActionListener {
                refresh()
            }
        }

    /**
     * Checkbox allowing for filtering out deployments from the target list.
     */
    private val showDeployments: JBCheckBox =
        JBCheckBox("Deployments", MirrordSettingsState.instance.mirrordState.showDeploymentsInSelection ?: true).apply {
            this.addActionListener {
                refresh()
            }
        }

    /**
     * Checkbox allowing for filtering out rollouts from the target list.
     */
    private val showRollouts: JBCheckBox =
        JBCheckBox("Rollouts", MirrordSettingsState.instance.mirrordState.showRolloutsInSelection ?: true).apply {
            this.addActionListener {
                refresh()
            }
        }

    /**
     * Text field allowing for searching targets by path.
     */
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

    /**
     * Label for `targetFilter` and `targetOptions`.
     */
    private val selectTargetLabel = JLabel("Select Target").apply {
        alignmentX = JLabel.LEFT_ALIGNMENT
        font = JBFont.create(font.deriveFont(Font.BOLD), false)
    }

    /**
     * Label for `namespaceOptions`.
     */
    private val selectNamespaceLabel = JLabel("Select Namespace").apply {
        alignmentX = JLabel.LEFT_ALIGNMENT
        font = JBFont.create(font.deriveFont(Font.BOLD), false)
    }

    /**
     * Small vertical gap between widgets.
     */
    private val verticalSeparator: Component
        get() = Box.createRigidArea(Dimension(0, 10))

    /**
     * Small horizontal gap between widgets.
     */
    private val horizontalSeparator: Component
        get() = Box.createRigidArea(Dimension(10, 0))

    init {
        title = "mirrord"
        refresh()
        init()
    }

    /**
     * Updates widgets' content based on what we fetched from the cluster (`fetched` field).
     */
    private fun refresh() {
        refreshing = true
        try {
            val shownTargets: List<FoundTarget> = fetched.getTargetsStoredOrFetch(
                getTargets,
                showPods.isSelected,
                showDeployments.isSelected,
                showRollouts.isSelected
            )

            val selectableTargets = shownTargets
                .asSequence()
                .filter { it.available }
                .map { it.path }
                .filter { targetFilter.text == TARGET_FILTER_PLACEHOLDER || it.contains(targetFilter.text) }
                .toMutableList()
                .apply {
                    // Here, for user convenience, we insert the last chosen target at the head of the list.
                    // Target is identified only by its path, no matter the namespace.
                    MirrordSettingsState.instance.mirrordState.lastChosenTarget?.let {
                        val idx = this.indexOf(it)
                        if (idx != -1) {
                            this.removeAt(idx)
                            this.add(0, it)
                        }
                    }
                    add(TARGETLESS_SELECTION_LABEL)
                }
                .toTypedArray()
            targetOptions.setListData(selectableTargets)

            namespaceOptions.removeAllItems()
            fetched.namespaces?.forEach { namespaceOptions.addItem(it) }
            fetched.currentNamespace?.let { namespaceOptions.selectedItem = it }
        } finally {
            refreshing = false
        }
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
                    add(namespaceOptions)
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

    /**
     * Displays the dialog and returns the user selection.
     *
     * Returns null if the user cancelled.
     */
    fun showAndGetSelection(): UserSelection? {
        if (!showAndGet()) {
            return null
        }

        MirrordSettingsState.instance.mirrordState.showPodsInSelection = showPods.isSelected
        MirrordSettingsState.instance.mirrordState.showDeploymentsInSelection = showDeployments.isSelected
        MirrordSettingsState.instance.mirrordState.showRolloutsInSelection = showRollouts.isSelected

        val target = if (targetOptions.isSelectionEmpty) {
            MirrordLogger.logger.info("No target specified - running targetless")
            project.service<MirrordProjectService>().notifier.notification(
                "No target specified, mirrord running targetless.",
                NotificationType.INFORMATION
            )
                .withDontShowAgain(MirrordSettingsState.NotificationId.RUNNING_TARGETLESS)
                .fire()

            null
        } else {
            MirrordSettingsState.instance.mirrordState.lastChosenTarget = targetOptions.selectedValue
            targetOptions.selectedValue.takeUnless { it == TARGETLESS_SELECTION_LABEL }
        }

        return UserSelection(target, fetched.currentNamespace)
    }
}

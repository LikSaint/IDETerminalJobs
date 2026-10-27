package dev.jobbar

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ex.CheckboxAction
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.util.ui.EditableModel
import javax.swing.SwingUtilities
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTabbedPane
import javax.swing.table.DefaultTableModel
import com.intellij.ui.table.JBTable

class JobEditorDialog(
    private val project: Project,
    source: JobDefinition? = null
) : DialogWrapper(project) {
    private val original = source?.copyDeep()
    private val id = source?.id ?: java.util.UUID.randomUUID().toString()
    private val nameField = JBTextField(source?.name ?: "")
    private val shortNameField = JBTextField(source?.shortName ?: "").apply { emptyText.text = "Defaults to the name" }
    private val commandField = JBTextField(source?.command ?: "")
    private val directoryField = JBTextField(source?.workingDirectory ?: "\$PROJECT_DIR\$")
    private val typeBox = ComboBox(DefaultComboBoxModel(JobType.entries.toTypedArray()))
    // Groups have their own dialog.
    private val modeBox = ComboBox(DefaultComboBoxModel(JobRunMode.entries.filter { it != JobRunMode.GROUP }.toTypedArray()))
    private val iconBox = JobIcons.comboBox(source?.icon.orEmpty(), project)
    private val iconOnly = JBCheckBox("Icon only: hide the text in toolbars", source?.iconOnly ?: false)
    private val enabled = JBCheckBox("Enabled", source?.enabled ?: true)
    private val readyPortField = JBTextField(source?.readyPort?.takeIf { it > 0 }?.toString().orEmpty(), 6).apply { emptyText.text = "e.g. 4200" }
    private val readyUrlField = JBTextField(source?.readyUrl.orEmpty()).apply { emptyText.text = "e.g. http://localhost:4200" }
    private val reuseConsole = JBCheckBox("Reuse the same console for subsequent runs", source?.reuseConsole ?: true)
    private val parameterField = JBTextField(source?.parameterName ?: "")
    private val runAllVariants = JBCheckBox("Add an “All” option that runs every variant in order", source?.runAllVariants ?: false)
    private val busyArea = JBTextArea(source?.busyPatterns ?: "", 5, 48)
    private val errorsArea = JBTextArea(source?.errorPatterns ?: "", 7, 48)
    private val recoveryArea = JBTextArea(source?.recoveryPatterns ?: "", 7, 48)
    // EditableModel enables the decorator's Move Up / Move Down buttons for a custom order.
    private val variantsModel = object : DefaultTableModel(arrayOf("Label", "Value / suffix"), 0), EditableModel {
        override fun isCellEditable(row: Int, column: Int): Boolean = true
        override fun addRow() = addRow(arrayOf("", ""))
        override fun exchangeRows(oldIndex: Int, newIndex: Int) = moveRow(oldIndex, oldIndex, newIndex)
        override fun canExchangeRows(oldIndex: Int, newIndex: Int): Boolean = !autoSort
    }
    private val variantsTable = JBTable(variantsModel)
    private var sortOrder = source?.variantSortOrder ?: VariantSortOrder.LABEL_ASC
    private var autoSort = source?.autoSortVariants ?: false
    private var sorting = false

    init {
        title = if (source == null) "Create Job" else "Edit Job"
        typeBox.selectedItem = source?.type ?: JobType.GENERIC
        modeBox.selectedItem = source?.runMode ?: JobRunMode.ONE_SHOT
        source?.variants?.forEach { variantsModel.addRow(arrayOf(it.label, it.value)) }

        typeBox.addActionListener {
            val selected = typeBox.selectedItem as JobType
            if (selected == JobType.CUSTOM || selected == JobType.GENERIC) return@addActionListener
            if (busyArea.text.isBlank() && errorsArea.text.isBlank() && recoveryArea.text.isBlank()) applyPreset(selected)
        }
        // Only one-shot commands finish with a success that can trigger the next variant.
        runAllVariants.isEnabled = modeBox.selectedItem == JobRunMode.ONE_SHOT
        modeBox.addActionListener { runAllVariants.isEnabled = modeBox.selectedItem == JobRunMode.ONE_SHOT }
        // With auto-sort on, re-sort after each edit. Deferred so a cell editor is never moved under the user.
        variantsModel.addTableModelListener {
            if (autoSort && !sorting) SwingUtilities.invokeLater { if (!variantsTable.isEditing) sortVariants() }
        }
        variantsTable.addPropertyChangeListener("tableCellEditor") {
            if (autoSort && !variantsTable.isEditing) SwingUtilities.invokeLater { sortVariants() }
        }
        init()
    }

    override fun createCenterPanel(): JComponent {
        val general = FormBuilder.createFormBuilder()
            .addLabeledComponent("Name:", nameField)
            .addLabeledComponent("Short name:", shortNameField)
            .addLabeledComponent("Icon:", iconBox)
            .addComponentToRightColumn(iconOnly)
            .addComponentToRightColumn(JBLabel("With an icon, toolbars show the status as a dot on it."))
            .addLabeledComponent("Job type:", typeBox)
            .addLabeledComponent("Run mode:", modeBox)
            .addLabeledComponent("Command:", commandField)
            .addLabeledComponent("Working directory:", directoryField)
            .addComponentToRightColumn(JBLabel("Use \$PROJECT_DIR\$ for the current project root."))
            .addComponent(reuseConsole)
            .addComponent(enabled)
            .addComponentToRightColumn(JBLabel("Disabled jobs stay in the list but are hidden from toolbars and shortcuts."))
            .panel

        val parametersTable = ToolbarDecorator.createDecorator(variantsTable)
            .setAddAction {
                variantsModel.addRow(arrayOf("", ""))
                val row = variantsModel.rowCount - 1
                variantsTable.selectionModel.setSelectionInterval(row, row)
                variantsTable.editCellAt(row, 0)
                variantsTable.editorComponent?.requestFocusInWindow()
            }
            .setRemoveAction { if (variantsTable.selectedRow >= 0) variantsModel.removeRow(variantsTable.selectedRow) }
            .setMoveUpActionUpdater { !autoSort }
            .setMoveDownActionUpdater { !autoSort }
            .addExtraAction(SortVariantsAction())
            .addExtraAction(AutoSortAction())
            .createPanel()
        val parameters = FormBuilder.createFormBuilder()
            .addLabeledComponent("Parameter name:", parameterField)
            .addComponent(JBLabel("Use \${name} in the command. If no placeholder exists, the value is appended as a suffix."))
            .addComponentFillVertically(parametersTable, 0)
            .addComponent(runAllVariants)
            .addComponent(JBLabel("Each variant starts after the previous one succeeds; the run stops at the first failure. One-shot jobs only."))
            .panel

        val resetButton = javax.swing.JButton("Reset to Type Preset").apply {
            addActionListener {
                val type = typeBox.selectedItem as JobType
                if ((busyArea.text.isNotBlank() || errorsArea.text.isNotBlank() || recoveryArea.text.isNotBlank()) &&
                    Messages.showYesNoDialog(project, "Replace the current status patterns?", "Reset Status Patterns", null) != Messages.YES
                ) return@addActionListener
                applyPreset(type)
            }
        }
        val status = FormBuilder.createFormBuilder()
            .addLabeledComponent("Build / rebuild patterns:", JBScrollPane(busyArea))
            .addLabeledComponent("Error patterns:", JBScrollPane(errorsArea))
            .addLabeledComponent("Recovery patterns:", JBScrollPane(recoveryArea))
            .addComponent(JBLabel("One pattern per line. Prefix a regular expression with regex:. Matching ignores ANSI terminal colors."))
            .addComponent(resetButton)
            .addSeparator()
            .addLabeledComponent("Ready when port is open:", readyPortField)
            .addLabeledComponent("Ready when URL responds:", readyUrlField)
            .addComponent(JBLabel("Tracked processes: checked every second until the first success, alongside the recovery patterns."))
            .panel

        val tabs = JTabbedPane().apply {
            addTab("General", general)
            addTab("Parameters", parameters)
            addTab("Status", status)
        }
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8)
            preferredSize = Dimension(JBUI.scale(680), JBUI.scale(470))
            add(tabs, BorderLayout.CENTER)
        }
    }

    private fun sortVariants() {
        if (sorting) return
        if (variantsTable.isEditing) variantsTable.cellEditor?.stopCellEditing()
        val rows = (0 until variantsModel.rowCount).map {
            JobVariant(variantsModel.getValueAt(it, 0)?.toString().orEmpty(), variantsModel.getValueAt(it, 1)?.toString().orEmpty())
        }
        val sorted = rows.sortedWith(sortOrder.comparator())
        if (sorted == rows) return
        val selected = variantsTable.selectedRow.takeIf { it >= 0 }?.let { rows[it] }
        sorting = true
        try {
            variantsModel.rowCount = 0
            sorted.forEach { variantsModel.addRow(arrayOf(it.label, it.value)) }
        } finally {
            sorting = false
        }
        selected?.let { sorted.indexOf(it) }?.takeIf { it >= 0 }?.let { variantsTable.selectionModel.setSelectionInterval(it, it) }
    }

    /** Sorts once by the picked order; the user can reorder freely afterwards unless auto-sort is on. */
    private inner class SortVariantsAction : DumbAwareAction("Sort Variants…", "Sort the variants", AllIcons.ObjectBrowser.Sorted) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) { e.presentation.isEnabled = variantsModel.rowCount > 1 || autoSort }

        override fun actionPerformed(e: AnActionEvent) {
            val options = DefaultActionGroup(VariantSortOrder.entries.map { order ->
                object : DumbAwareAction(order.label, null, if (order == sortOrder) AllIcons.Actions.Checked else null) {
                    override fun actionPerformed(e: AnActionEvent) {
                        sortOrder = order
                        sortVariants()
                    }
                }
            })
            val popup = JBPopupFactory.getInstance().createActionGroupPopup(
                "Sort Variants", options, e.dataContext, JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true
            )
            val source = e.inputEvent?.component
            if (source != null) popup.showUnderneathOf(source) else popup.showInBestPositionFor(e.dataContext)
        }
    }

    private inner class AutoSortAction : CheckboxAction("Auto-sort", "Always keep variants in the selected sort order", null) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun isSelected(e: AnActionEvent): Boolean = autoSort

        override fun update(e: AnActionEvent) {
            super.update(e)
            e.presentation.text = if (autoSort) "Auto-sort: ${sortOrder.label}" else "Auto-sort"
        }

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            autoSort = state
            if (state) sortVariants()
        }
    }

    private fun applyPreset(type: JobType) {
        val preset = JobPresets.forType(type)
        busyArea.text = preset.busy
        errorsArea.text = preset.errors
        recoveryArea.text = preset.recovery
    }

    override fun doValidate(): ValidationInfo? {
        if (nameField.text.isBlank()) return ValidationInfo("Enter a job name", nameField)
        if (commandField.text.isBlank()) return ValidationInfo("Enter a command", commandField)
        val parameter = parameterField.text.trim()
        if (variantsModel.rowCount > 0 && parameter.isBlank()) return ValidationInfo("Enter a parameter name", parameterField)
        val port = readyPortField.text.trim()
        if (port.isNotEmpty() && port.toIntOrNull()?.takeIf { it in 1..65535 } == null) return ValidationInfo("Enter a port between 1 and 65535", readyPortField)
        val url = readyUrlField.text.trim()
        if (url.isNotEmpty() && runCatching { java.net.URI(url).toURL() }.isFailure) return ValidationInfo("Enter a full URL, e.g. http://localhost:4200", readyUrlField)
        return null
    }

    fun result(): JobDefinition = JobDefinition(
        id = id,
        name = nameField.text.trim(),
        shortName = shortNameField.text.trim(),
        command = commandField.text.trim(),
        workingDirectory = directoryField.text.trim().ifBlank { "\$PROJECT_DIR\$" },
        runMode = modeBox.selectedItem as JobRunMode,
        type = typeBox.selectedItem as JobType,
        reuseConsole = reuseConsole.isSelected,
        parameterName = parameterField.text.trim(),
        variants = (0 until variantsModel.rowCount).mapNotNull { row ->
            val label = variantsModel.getValueAt(row, 0)?.toString()?.trim().orEmpty()
            val value = variantsModel.getValueAt(row, 1)?.toString()?.trim().orEmpty()
            if (label.isBlank() || value.isBlank()) null else JobVariant(label, value)
        }.let { if (autoSort) it.sortedWith(sortOrder.comparator()) else it }.toMutableList(),
        runAllVariants = runAllVariants.isSelected,
        variantSortOrder = sortOrder,
        autoSortVariants = autoSort,
        busyPatterns = busyArea.text.trim(),
        errorPatterns = errorsArea.text.trim(),
        recoveryPatterns = recoveryArea.text.trim(),
        icon = JobIcons.selectedKey(iconBox),
        iconOnly = iconOnly.isSelected,
        enabled = enabled.isSelected,
        readyPort = readyPortField.text.trim().toIntOrNull() ?: 0,
        readyUrl = readyUrlField.text.trim()
    )
}

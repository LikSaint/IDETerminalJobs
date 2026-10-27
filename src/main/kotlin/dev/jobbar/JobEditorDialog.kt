package dev.jobbar

import com.intellij.openapi.project.Project
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
    private val commandField = JBTextField(source?.command ?: "")
    private val directoryField = JBTextField(source?.workingDirectory ?: "\$PROJECT_DIR\$")
    private val typeBox = ComboBox(DefaultComboBoxModel(JobType.entries.toTypedArray()))
    private val modeBox = ComboBox(DefaultComboBoxModel(JobRunMode.entries.toTypedArray()))
    private val reuseConsole = JBCheckBox("Reuse the same console for subsequent runs", source?.reuseConsole ?: true)
    private val parameterField = JBTextField(source?.parameterName ?: "")
    private val runAllVariants = JBCheckBox("Add an “All” option that runs every variant in order", source?.runAllVariants ?: false)
    private val busyArea = JBTextArea(source?.busyPatterns ?: "", 5, 48)
    private val errorsArea = JBTextArea(source?.errorPatterns ?: "", 7, 48)
    private val recoveryArea = JBTextArea(source?.recoveryPatterns ?: "", 7, 48)
    private val variantsModel = object : DefaultTableModel(arrayOf("Label", "Value / suffix"), 0) {
        override fun isCellEditable(row: Int, column: Int): Boolean = true
    }
    private val variantsTable = JBTable(variantsModel)

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
        init()
    }

    override fun createCenterPanel(): JComponent {
        val general = FormBuilder.createFormBuilder()
            .addLabeledComponent("Name:", nameField)
            .addLabeledComponent("Job type:", typeBox)
            .addLabeledComponent("Run mode:", modeBox)
            .addLabeledComponent("Command:", commandField)
            .addLabeledComponent("Working directory:", directoryField)
            .addComponent(reuseConsole)
            .addComponentToRightColumn(JBLabel("Use \$PROJECT_DIR\$ for the current project root."))
            .panel

        val parametersTable = ToolbarDecorator.createDecorator(variantsTable)
            .setAddAction { variantsModel.addRow(arrayOf("", "")) }
            .setRemoveAction { if (variantsTable.selectedRow >= 0) variantsModel.removeRow(variantsTable.selectedRow) }
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
        return null
    }

    fun result(): JobDefinition = JobDefinition(
        id = id,
        name = nameField.text.trim(),
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
        }.toMutableList(),
        runAllVariants = runAllVariants.isSelected,
        busyPatterns = busyArea.text.trim(),
        errorPatterns = errorsArea.text.trim(),
        recoveryPatterns = recoveryArea.text.trim()
    )
}

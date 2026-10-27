package dev.jobbar

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.EditableModel
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.ButtonGroup
import javax.swing.DefaultCellEditor
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableCellEditor

/** Creates or edits a launcher (a "group" job): starts other jobs all at once or one after another. */
class GroupEditorDialog(
    project: Project,
    source: JobDefinition? = null
) : DialogWrapper(project) {
    private val id = source?.id ?: java.util.UUID.randomUUID().toString()
    // Members can be any job except groups (no nesting, so no cycles).
    private val jobs = JobBarService.get(project).jobs().filter { !it.isGroup }
    private val nameField = JBTextField(source?.name ?: "").apply { emptyText.text = "e.g. Default" }
    private val shortNameField = JBTextField(source?.shortName ?: "").apply { emptyText.text = "Defaults to the name" }
    // Launchers show their own state icons (start / loader / stop), so only the text can be hidden.
    private val iconOnly = JBCheckBox("Icon only: hide the text in toolbars", source?.iconOnly ?: false)
    private val enabled = JBCheckBox("Enabled", source?.enabled ?: true)
    private val sequential = JBRadioButton("One after another: start the next job when the previous one is ready", source?.groupSequential ?: true)
    private val parallel = JBRadioButton("All at once", !(source?.groupSequential ?: true))
    private val membersModel = MembersModel(source?.members?.map { it.copy() }?.toMutableList() ?: mutableListOf())
    private val membersTable = object : JBTable(membersModel) {
        override fun getCellEditor(row: Int, column: Int): TableCellEditor {
            val member = membersModel.rows[row]
            return if (column == 0) comboEditor(jobComboBox(), member.jobId) else comboEditor(variantComboBox(member.jobId), member.variant)
        }
    }.apply {
        setDefaultRenderer(Any::class.java, object : javax.swing.table.DefaultTableCellRenderer() {
            override fun setValue(value: Any?) {
                text = value?.toString().orEmpty()
            }
        })
    }

    init {
        title = if (source == null) "Create Launcher" else "Edit Launcher"
        ButtonGroup().apply { add(sequential); add(parallel) }
        init()
    }

    override fun createCenterPanel(): JComponent {
        val table = ToolbarDecorator.createDecorator(membersTable)
            .setAddAction {
                membersModel.addRow()
                val row = membersModel.rowCount - 1
                membersTable.selectionModel.setSelectionInterval(row, row)
                membersTable.editCellAt(row, 0)
            }
            .createPanel()
        val mode = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
            add(sequential)
            add(parallel)
        }
        val form = FormBuilder.createFormBuilder()
            .addLabeledComponent("Name:", nameField)
            .addLabeledComponent("Short name:", shortNameField)
            .addComponentToRightColumn(iconOnly)
            .addComponent(enabled)
            .addSeparator()
            .addLabeledComponent("Start:", mode)
            .addComponent(JBLabel("A one-shot job is done when it succeeds, a tracked process when it is ready. Starting stops at the first failure."))
            .addLabeledComponentFillVertically("Jobs:", table)
            .panel
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8)
            preferredSize = Dimension(JBUI.scale(620), JBUI.scale(460))
            add(form, BorderLayout.CENTER)
        }
    }

    // The table shows names, so the editor selects the stored id / value itself rather than the cell text.
    private fun comboEditor(combo: ComboBox<String>, current: String): TableCellEditor = object : DefaultCellEditor(combo) {
        override fun getTableCellEditorComponent(table: javax.swing.JTable?, value: Any?, isSelected: Boolean, row: Int, column: Int): java.awt.Component {
            val component = super.getTableCellEditorComponent(table, value, isSelected, row, column)
            combo.selectedItem = current
            return component
        }
    }

    private fun jobComboBox(): ComboBox<String> = ComboBox(jobs.map { it.id }.toTypedArray()).apply {
        renderer = SimpleListCellRenderer.create { label, jobId, _ -> label.text = jobName(jobId) }
    }

    private fun variantComboBox(jobId: String): ComboBox<String> {
        val job = jobs.find { it.id == jobId }
        val options = buildList {
            add("")
            if (job != null && job.variants.isNotEmpty()) {
                if (job.runMode == JobRunMode.ONE_SHOT) add(GroupMember.ALL_VARIANTS)
                job.variants.forEach { add(it.value) }
            }
        }
        return ComboBox(options.toTypedArray()).apply {
            renderer = SimpleListCellRenderer.create { label, value, _ -> label.text = variantName(job, value.orEmpty()) }
        }
    }

    private fun jobName(jobId: String?): String = jobs.find { it.id == jobId }?.name ?: "Choose a job…"

    private fun variantName(job: JobDefinition?, value: String): String = when (value) {
        "" -> if (job?.variants.isNullOrEmpty()) "—" else "No variant"
        GroupMember.ALL_VARIANTS -> "All variants"
        else -> job?.variants?.find { it.value == value }?.label ?: value
    }

    private inner class MembersModel(val rows: MutableList<GroupMember>) : AbstractTableModel(), EditableModel {
        override fun getRowCount(): Int = rows.size
        override fun getColumnCount(): Int = 2
        override fun getColumnName(column: Int): String = if (column == 0) "Job" else "Variant"
        override fun isCellEditable(row: Int, column: Int): Boolean = true

        // Shown as names; the table stores job ids and variant values.
        override fun getValueAt(row: Int, column: Int): Any {
            val member = rows[row]
            return if (column == 0) jobName(member.jobId) else variantName(jobs.find { it.id == member.jobId }, member.variant)
        }

        override fun setValueAt(value: Any?, row: Int, column: Int) {
            val member = rows[row]
            if (column == 0) {
                val jobId = value as? String ?: return
                if (jobId != member.jobId) member.variant = ""
                member.jobId = jobId
            } else {
                member.variant = value as? String ?: ""
            }
            fireTableRowsUpdated(row, row)
        }

        override fun addRow() {
            rows += GroupMember()
            fireTableRowsInserted(rows.lastIndex, rows.lastIndex)
        }

        override fun removeRow(index: Int) {
            rows.removeAt(index)
            fireTableRowsDeleted(index, index)
        }

        override fun exchangeRows(oldIndex: Int, newIndex: Int) {
            rows.add(newIndex, rows.removeAt(oldIndex))
            fireTableDataChanged()
        }

        override fun canExchangeRows(oldIndex: Int, newIndex: Int): Boolean = true
    }

    override fun doValidate(): ValidationInfo? {
        if (nameField.text.isBlank()) return ValidationInfo("Enter a launcher name", nameField)
        membersTable.cellEditor?.stopCellEditing()
        if (membersModel.rows.none { member -> jobs.any { it.id == member.jobId } }) return ValidationInfo("Add at least one job", membersTable)
        return null
    }

    fun result(): JobDefinition = JobDefinition(
        id = id,
        name = nameField.text.trim(),
        shortName = shortNameField.text.trim(),
        icon = "",
        iconOnly = iconOnly.isSelected,
        enabled = enabled.isSelected,
        runMode = JobRunMode.GROUP,
        command = "",
        members = membersModel.rows.filter { member -> jobs.any { it.id == member.jobId } }.map { it.copy() }.toMutableList(),
        groupSequential = sequential.isSelected
    )
}

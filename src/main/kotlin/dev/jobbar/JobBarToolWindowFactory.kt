package dev.jobbar

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.util.NlsActions
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.openapi.vfs.LocalFileSystem
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Color
import java.awt.Container
import java.awt.Dimension
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Scrollable
import javax.swing.ScrollPaneConstants

class JobBarToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val view = JobBarView(project)
        toolWindow.setAdditionalGearActions(view.gearActions())
        val content = ContentFactory.getInstance().createContent(view.component, "Jobs", false).apply {
            isCloseable = false
        }
        toolWindow.contentManager.addContent(content)
    }
}

private class JobBarView(private val project: Project) {
    private val service = JobBarService.get(project)
    private val runtimes = JobRuntimeManager.get(project)
    private val jobActions = JobActions.get(project)
    private val rows = object : JPanel(), Scrollable {
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = JBUI.scale(32)
        override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = visibleRect.height
        override fun getScrollableTracksViewportWidth(): Boolean = true
        override fun getScrollableTracksViewportHeight(): Boolean = false
    }.apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(4)
    }
    private var hoveredRow: JComponent? = null

    val component: JComponent = com.intellij.openapi.ui.SimpleToolWindowPanel(true, true).apply {
        setContent(JBScrollPane(
            rows,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        ).apply { border = JBUI.Borders.empty() })
    }

    fun gearActions(): ActionGroup = DefaultActionGroup().apply {
        add(AddJobAction())
        add(AddGroupAction())
        add(AddFromPackageJsonAction())
        addSeparator()
        add(ShowInMainToolbarAction())
        add(JobSoundsToggleAction())
        addSeparator()
        add(ImportJobsAction())
        add(ExportJobsAction())
    }

    init {
        service.addListener(::refresh)
        runtimes.addListener(::refresh)
        refresh()
    }

    private fun refresh() {
        setHoveredRow(null)
        rows.removeAll()
        if (service.jobs().isEmpty()) {
            rows.add(JBLabel("No jobs yet. Use ⋮ → Add Job to create one.", SwingConstants.CENTER).apply {
                alignmentX = Component.CENTER_ALIGNMENT
                border = JBUI.Borders.empty(24)
            })
        } else {
            service.jobs().forEach { rows.add(createRow(it)) }
            rows.add(Box.createVerticalGlue())
        }
        rows.revalidate()
        rows.repaint()
    }

    private fun createRow(job: JobDefinition): JComponent {
        val runtime = runtimes.runtime(job)
        val row = object : JBPanel<JBPanel<*>>(BorderLayout()) {
            override fun paintComponent(graphics: Graphics) {
                super.paintComponent(graphics)
                if (getClientProperty("JobBar.hovered") == true) {
                    val g = graphics.create() as Graphics2D
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g.color = JBUI.CurrentTheme.Tree.background(true, false)
                    val inset = JBUI.scale(4)
                    val arc = JBUI.CurrentTheme.Tree.ARC.get()
                    g.fillRoundRect(inset, 1, width - inset * 2, height - 2, arc, arc)
                    g.dispose()
                }
            }
        }.apply {
            border = JBUI.Borders.empty(1, 6)
            preferredSize = Dimension(JBUI.scale(320), JBUI.scale(32))
            minimumSize = Dimension(0, JBUI.scale(32))
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(32))
            isOpaque = false
        }
        val title = SimpleColoredComponent().apply {
            icon = if (job.isGroup) AllIcons.Actions.RunAll else JobIcons.find(job.icon, project)
            append(job.name, if (job.enabled) SimpleTextAttributes.REGULAR_ATTRIBUTES else SimpleTextAttributes.GRAYED_ATTRIBUTES)
            if (!job.enabled) append("  disabled", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            toolTipText = when {
                job.isGroup -> "Launcher: " + job.members.mapNotNull { m -> service.jobs().find { it.id == m.jobId }?.name }.joinToString(if (job.groupSequential) " → " else ", ")
                else -> runtime.lastCommand.ifBlank { job.command }
            }
        }
        row.add(title, BorderLayout.CENTER)

        val actions = DefaultActionGroup()
        if (runtime.inSequence) {
            actions.add(jobActions.stopAction(job))
        } else if ((job.runMode == JobRunMode.TRACKED || job.isGroup) && runtime.isActive()) {
            actions.add(jobActions.stopAction(job))
            actions.add(jobActions.restartAction(job))
        } else {
            actions.add(jobActions.runAction(job))
        }
        actions.add(jobActions.openConsoleAction(job))
        actions.add(jobActions.editAction(job))
        val toolbar = ActionManager.getInstance().createActionToolbar("JobBar.JobRow", actions, true)
        toolbar.targetComponent = row
        toolbar.component.border = JBUI.Borders.empty()
        toolbar.component.preferredSize = Dimension(JBUI.scale(112), JBUI.scale(28))
        toolbar.component.minimumSize = Dimension(JBUI.scale(112), JBUI.scale(28))
        toolbar.component.maximumSize = Dimension(JBUI.scale(112), JBUI.scale(28))
        val controls = JPanel(BorderLayout()).apply {
            isOpaque = false
            preferredSize = Dimension(JBUI.scale(132), JBUI.scale(28))
            minimumSize = Dimension(JBUI.scale(132), JBUI.scale(28))
            maximumSize = Dimension(JBUI.scale(132), JBUI.scale(28))
            add(statusIndicator(runtime), BorderLayout.WEST)
            add(toolbar.component, BorderLayout.CENTER)
        }
        row.add(controls, BorderLayout.EAST)
        UIUtil.setNotOpaqueRecursively(row)
        installRowHover(row, row)

        row.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) = maybePopup(event)
            override fun mouseReleased(event: MouseEvent) = maybePopup(event)
            private fun maybePopup(event: MouseEvent) {
                if (!event.isPopupTrigger) return
                ActionManager.getInstance().createActionPopupMenu(
                    ActionPlaces.POPUP,
                    jobActions.contextActions(job)
                ).component.show(event.component, event.x, event.y)
            }
        })
        title.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) = maybePopup(event)
            override fun mouseReleased(event: MouseEvent) = maybePopup(event)
            private fun maybePopup(event: MouseEvent) {
                if (!event.isPopupTrigger) return
                ActionManager.getInstance().createActionPopupMenu(
                    ActionPlaces.POPUP,
                    jobActions.contextActions(job)
                ).component.show(event.component, event.x, event.y)
            }
        })
        return row
    }

    private fun installRowHover(row: JComponent, component: Component) {
        val listener = object : MouseAdapter() {
            override fun mouseEntered(event: MouseEvent) {
                setHoveredRow(row)
            }

            override fun mouseExited(event: MouseEvent) {
                val screenPoint = Point(event.xOnScreen, event.yOnScreen)
                SwingUtilities.invokeLater {
                    SwingUtilities.convertPointFromScreen(screenPoint, row)
                    if (!row.contains(screenPoint) && hoveredRow === row) setHoveredRow(null)
                }
            }
        }
        component.addMouseListener(listener)
        if (component is Container) component.components.forEach { installRowHover(row, it) }
    }

    private fun setHoveredRow(row: JComponent?) {
        if (hoveredRow === row) return
        hoveredRow?.let {
            it.putClientProperty("JobBar.hovered", false)
            it.repaint()
        }
        hoveredRow = row
        row?.let {
            it.putClientProperty("JobBar.hovered", true)
            it.repaint()
        }
    }

    private fun statusIndicator(runtime: JobRuntime): JComponent = JBLabel().apply {
        horizontalAlignment = SwingConstants.CENTER
        preferredSize = Dimension(JBUI.scale(20), JBUI.scale(28))
        minimumSize = preferredSize
        maximumSize = preferredSize
        isOpaque = false
        icon = when (runtime.status) {
            JobRuntimeStatus.STARTING, JobRuntimeStatus.RUNNING -> AnimatedIcon.Default.INSTANCE
            JobRuntimeStatus.IDLE, JobRuntimeStatus.STOPPED -> null
            else -> StatusDotIcon(statusColor(runtime.status))
        }
        toolTipText = statusDescription(runtime)
    }

    private inner class AddGroupAction : DumbAwareAction("Add Launcher", "Create a launcher that starts several jobs with one click", AllIcons.Actions.RunAll) {
        override fun actionPerformed(e: AnActionEvent) {
            val dialog = GroupEditorDialog(project)
            if (dialog.showAndGet()) service.save(dialog.result())
        }
    }

    private inner class AddFromPackageJsonAction : DumbAwareAction("Add from package.json…", "Create jobs from package.json scripts", AllIcons.FileTypes.Json) {
        override fun actionPerformed(e: AnActionEvent) {
            val candidates = PackageJsonImport.scan(project, service.jobs())
            if (candidates.isEmpty()) {
                Messages.showInfoMessage(project, "No package.json scripts were found in this project.", "Add from package.json")
                return
            }
            val dialog = PackageJsonImportDialog(project, candidates)
            if (dialog.showAndGet()) service.addAll(dialog.selectedJobs())
        }
    }

    private inner class AddJobAction : DumbAwareAction("Add Job", "Create a command job", AllIcons.General.Add) {
        override fun actionPerformed(e: AnActionEvent) {
            val dialog = JobEditorDialog(project)
            if (dialog.showAndGet()) service.save(dialog.result())
        }
    }

    private inner class ExportJobsAction : DumbAwareAction("Export Jobs…", "Export all Job Bar jobs", AllIcons.Actions.MenuSaveall) {
        override fun actionPerformed(e: AnActionEvent) {
            val descriptor = FileSaverDescriptor("Export Job Bar Jobs", "Choose where to save the job definitions", "xml")
            val wrapper = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project).save("jobbar-jobs.xml") ?: return
            runCatching {
                val path = wrapper.file.toPath()
                path.parent?.let { Files.createDirectories(it) }
                Files.writeString(path, service.exportXml(), StandardCharsets.UTF_8)
                LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
            }
                .onFailure { Messages.showErrorDialog(project, it.message ?: "Unable to export jobs", "Export Jobs") }
        }
    }

    private inner class ImportJobsAction : DumbAwareAction("Import Jobs…", "Import Job Bar jobs", AllIcons.Actions.MenuOpen) {
        override fun actionPerformed(e: AnActionEvent) {
            val path = try {
                chooseFile(component, "Import Job Bar Jobs", project.basePath, "Job Bar XML", listOf("xml")) ?: return
            } catch (error: IllegalArgumentException) {
                Messages.showErrorDialog(project, error.message ?: "Unable to import jobs", "Import Jobs")
                return
            }
            try {
                val xml = Files.readString(path, StandardCharsets.UTF_8)
                if (Messages.showYesNoDialog(project, "Replace the current jobs with the imported definitions?", "Import Jobs", null) == Messages.YES) {
                    service.importXml(xml)
                }
            } catch (error: Exception) {
                Messages.showErrorDialog(project, error.message ?: "Unable to import jobs", "Import Jobs")
            }
        }
    }
}

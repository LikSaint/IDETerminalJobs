package dev.jobbar

import com.intellij.openapi.project.Project
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.SwingUtilities

val activeStatuses = setOf(JobRuntimeStatus.STARTING, JobRuntimeStatus.RUNNING, JobRuntimeStatus.READY, JobRuntimeStatus.WARNING)

fun JobRuntime.isActive(): Boolean = inSequence || status in activeStatuses

fun JobRuntime.isBusy(): Boolean = inSequence || status == JobRuntimeStatus.STARTING || status == JobRuntimeStatus.RUNNING

fun statusColor(status: JobRuntimeStatus): Color = when (status) {
    JobRuntimeStatus.READY, JobRuntimeStatus.SUCCESS -> JBUI.CurrentTheme.ProgressBar.PASSED
    JobRuntimeStatus.WARNING -> JBUI.CurrentTheme.ProgressBar.WARNING
    JobRuntimeStatus.ERROR -> JBUI.CurrentTheme.ProgressBar.FAILED
    JobRuntimeStatus.STARTING, JobRuntimeStatus.RUNNING -> UIUtil.getLabelForeground()
    JobRuntimeStatus.IDLE, JobRuntimeStatus.STOPPED -> UIUtil.getContextHelpForeground()
}

fun statusDescription(runtime: JobRuntime): String? {
    if (runtime.inSequence) {
        val current = runtime.sequence[runtime.sequenceIndex]
        return "Running all · ${runtime.sequenceIndex + 1}/${runtime.sequence.size} · ${current.label}"
    }
    return when (runtime.status) {
        JobRuntimeStatus.STARTING -> "Starting · waiting for a status match"
        JobRuntimeStatus.RUNNING -> "Running"
        JobRuntimeStatus.READY -> "Running and tracked"
        JobRuntimeStatus.WARNING -> "Running with errors"
        JobRuntimeStatus.SUCCESS -> "Completed successfully"
        JobRuntimeStatus.ERROR -> "Failed"
        JobRuntimeStatus.IDLE, JobRuntimeStatus.STOPPED -> null
    }
}

class StatusDotIcon(private val color: Color) : Icon {
    override fun getIconWidth(): Int = JBUI.scale(8)
    override fun getIconHeight(): Int = JBUI.scale(8)

    override fun paintIcon(component: Component?, graphics: Graphics, x: Int, y: Int) {
        val g = graphics.create() as Graphics2D
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = color
        g.fillOval(x, y, iconWidth, iconHeight)
        g.dispose()
    }
}

/** Short, status-colored job label used by the compact view and the main toolbar. Click opens the job menu. */
class JobChip : JBPanel<JobChip>(BorderLayout()) {
    private val label = JBLabel().apply { iconTextGap = JBUI.scale(4) }
    private var hovered = false
    private var project: Project? = null
    private var job: JobDefinition? = null

    init {
        isOpaque = false
        border = JBUI.Borders.empty(3, 8)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        add(label, BorderLayout.CENTER)
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(event: MouseEvent) = setHovered(true)
            override fun mouseExited(event: MouseEvent) = setHovered(false)
            override fun mousePressed(event: MouseEvent) {
                // Left click and the platform popup trigger both open the job menu.
                if (SwingUtilities.isLeftMouseButton(event) || event.isPopupTrigger) showMenu()
            }
            override fun mouseReleased(event: MouseEvent) {
                if (event.isPopupTrigger) showMenu()
            }
        })
    }

    fun bind(project: Project, job: JobDefinition, runtime: JobRuntime) {
        this.project = project
        this.job = job
        label.text = job.compactLabel
        label.foreground = statusColor(runtime.status)
        label.icon = if (runtime.isBusy()) AnimatedIcon.Default.INSTANCE else null
        toolTipText = listOfNotNull(job.name, statusDescription(runtime)).joinToString(" · ")
        revalidate()
        repaint()
    }

    private fun showMenu() {
        val project = project ?: return
        val job = job ?: return
        JobActions.get(project).showJobMenu(job, this)
    }

    private fun setHovered(value: Boolean) {
        hovered = value
        repaint()
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val g = graphics.create() as Graphics2D
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val arc = JBUI.CurrentTheme.Tree.ARC.get()
        if (hovered) {
            g.color = JBUI.CurrentTheme.ActionButton.hoverBackground()
            g.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
        }
        g.color = JBUI.CurrentTheme.ActionButton.hoverBorder()
        g.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
        g.dispose()
    }
}

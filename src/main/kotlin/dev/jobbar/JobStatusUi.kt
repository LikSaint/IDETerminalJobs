package dev.jobbar

import com.intellij.ide.DataManager
import com.intellij.openapi.project.Project
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
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
import javax.swing.Timer

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

/** Dot color for icon mode; no dot while idle or stopped. */
fun statusDotColor(status: JobRuntimeStatus): Color? = when (status) {
    JobRuntimeStatus.READY, JobRuntimeStatus.SUCCESS -> DOT_GREEN
    JobRuntimeStatus.WARNING -> DOT_ORANGE
    JobRuntimeStatus.ERROR -> DOT_RED
    JobRuntimeStatus.IDLE, JobRuntimeStatus.STOPPED, JobRuntimeStatus.STARTING, JobRuntimeStatus.RUNNING -> null
}

// Brighter than the theme's progress colors so a 5 px dot stays visible (light theme / dark theme).
private val DOT_GREEN = JBColor(Color(0x2FB344), Color(0x4CE15A))
private val DOT_ORANGE = JBColor(Color(0xF08C00), Color(0xFFB224))
private val DOT_RED = JBColor(Color(0xE5383B), Color(0xFF5C5C))

fun statusDescription(runtime: JobRuntime): String? {
    if (runtime.groupSummary.isNotEmpty() && runtime.status != JobRuntimeStatus.IDLE) {
        return "${statusDescriptionOf(runtime.status)} · ${runtime.groupSummary}"
    }
    if (runtime.inSequence) {
        val current = runtime.sequence[runtime.sequenceIndex]
        return "Running all · ${runtime.sequenceIndex + 1}/${runtime.sequence.size} · ${current.label}"
    }
    return statusDescriptionOf(runtime.status)
}

private fun statusDescriptionOf(status: JobRuntimeStatus): String? {
    return when (status) {
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
    private val label = object : JBLabel() {
        override fun processMouseEvent(event: MouseEvent) {
            if (!interceptMenuGesture(event)) super.processMouseEvent(event)
        }
    }.apply { iconTextGap = JBUI.scale(4) }
    private val spinner = Timer(50) { label.repaint() }
    private var hovered = false
    private var project: Project? = null
    private var job: JobDefinition? = null

    init {
        isOpaque = false
        border = JBUI.Borders.empty(3, 8)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        add(label, BorderLayout.CENTER)
        // Toolbars may attach their own listeners (e.g. tooltips) to the inner label, which then swallows
        // the events. Listen on both, and drop the hover only when the pointer really leaves the chip.
        val listener = object : MouseAdapter() {
            override fun mouseEntered(event: MouseEvent) = setHovered(true)
            override fun mouseExited(event: MouseEvent) {
                val point = SwingUtilities.convertPoint(event.component, event.point, this@JobChip)
                if (!contains(point)) setHovered(false)
            }
            // A plain left click runs the job; menu gestures are handled in interceptMenuGesture().
            override fun mousePressed(event: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(event) && !event.isControlDown && !event.isPopupTrigger) runOrShowMenu()
            }
        }
        addMouseListener(listener)
        label.addMouseListener(listener)
    }

    fun bind(project: Project, job: JobDefinition, runtime: JobRuntime) {
        this.project = project
        this.job = job
        // The busy arc is drawn from the clock; repaint only while it is shown.
        val spinning = runtime.isBusy() && JobIcons.find(job.icon, project) != null
        if (spinning) spinner.start() else spinner.stop()
        val icon = JobIcons.find(job.icon, project)
        if (icon != null) {
            // Icon mode: the status is a dot on the icon (a loader while busy); the text keeps its normal color.
            label.icon = if (runtime.isBusy()) BusyBadgeIcon(icon) else StatusBadgeIcon(icon, statusDotColor(runtime.status))
            label.text = if (job.iconOnly) "" else job.compactLabel
            label.foreground = if (job.enabled) UIUtil.getLabelForeground() else UIUtil.getContextHelpForeground()
        } else {
            label.text = job.compactLabel
            label.foreground = statusColor(runtime.status)
            label.icon = if (runtime.isBusy()) AnimatedIcon.Default.INSTANCE else null
        }
        val hint = if (runtime.isActive()) "Click or right-click for actions" else "Click to run · right-click for actions"
        toolTipText = "<html>" + listOfNotNull(job.name, statusDescription(runtime)).joinToString(" · ") + "<br><small>$hint</small></html>"
        revalidate()
        repaint()
    }

    override fun removeNotify() {
        spinner.stop()
        super.removeNotify()
    }

    override fun processMouseEvent(event: MouseEvent) {
        if (!interceptMenuGesture(event)) super.processMouseEvent(event)
    }

    /**
     * Right click (or the popup trigger, e.g. Ctrl+click on macOS) opens the job menu. Such events are not passed
     * on to any listener: the main toolbar adds its own popup handler ("Customize Toolbar…") to our components,
     * and it ignores consumed events, so it would open a second menu on top of ours.
     */
    private fun interceptMenuGesture(event: MouseEvent): Boolean {
        val gesture = event.isPopupTrigger || SwingUtilities.isRightMouseButton(event) ||
            (SwingUtilities.isLeftMouseButton(event) && event.isControlDown)
        if (!gesture) return false
        if (event.id == MouseEvent.MOUSE_PRESSED) showMenu()
        event.consume()
        return true
    }

    /** Starts an idle job (asking for a variant first when it has any); a running one opens its menu instead. */
    private fun runOrShowMenu() {
        val project = project ?: return
        val job = job ?: return
        val actions = JobActions.get(project)
        if (JobRuntimeManager.get(project).runtime(job).isActive()) actions.showJobMenu(job, this)
        else actions.runInteractive(job, DataManager.getInstance().getDataContext(this), anchor = this)
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

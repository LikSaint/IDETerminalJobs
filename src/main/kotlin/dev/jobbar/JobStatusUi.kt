package dev.jobbar

import com.intellij.ide.DataManager
import com.intellij.icons.AllIcons
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

/**
 * Short, status-colored job label used by the IDE toolbars.
 *
 * Left click acts on release: an idle job runs (variants first), a running job or launcher stops. Holding the button
 * on a running one switches the icon to "restart" and releasing restarts it. Moving the pointer off the label while
 * the button is down cancels. Right click opens the job menu.
 */
class JobChip : JBPanel<JobChip>(BorderLayout()) {
    private val label = object : JBLabel() {
        override fun processMouseEvent(event: MouseEvent) {
            if (!interceptMenuGesture(event)) super.processMouseEvent(event)
        }
    }.apply { iconTextGap = JBUI.scale(4) }
    private val spinner = Timer(50) { label.repaint() }
    private val longPress = Timer(LONG_PRESS_MS) {
        holding = true
        render()
    }.apply { isRepeats = false }
    private var hovered = false
    // Left button is down on the chip (and not cancelled by leaving it); holding = long press reached.
    private var pressed = false
    private var holding = false
    private var project: Project? = null
    private var job: JobDefinition? = null
    private var runtime: JobRuntime? = null

    init {
        isOpaque = false
        border = JBUI.Borders.empty(3, 8)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        add(label, BorderLayout.CENTER)
        // Toolbars may attach their own listeners (e.g. tooltips) to the inner label, which then swallows
        // the events. Listen on both, and judge "inside" against the whole chip.
        val listener = object : MouseAdapter() {
            override fun mouseEntered(event: MouseEvent) = setHovered(true)
            override fun mouseExited(event: MouseEvent) {
                if (isInside(event)) return
                setHovered(false)
                cancelPress()
            }
            override fun mousePressed(event: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(event) || event.isControlDown || event.isPopupTrigger) return
                pressed = true
                holding = false
                if (runtime?.isActive() == true) longPress.restart()
                render()
            }
            override fun mouseDragged(event: MouseEvent) {
                if (pressed && !isInside(event)) cancelPress()
            }
            override fun mouseReleased(event: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(event) || !pressed) return
                val restart = holding
                cancelPress()
                if (isInside(event)) perform(restart)
            }
        }
        addMouseListener(listener)
        addMouseMotionListener(listener)
        label.addMouseListener(listener)
        label.addMouseMotionListener(listener)
    }

    fun bind(project: Project, job: JobDefinition, runtime: JobRuntime) {
        this.project = project
        this.job = job
        this.runtime = runtime
        render()
    }

    private fun render() {
        val project = project ?: return
        val job = job ?: return
        val runtime = runtime ?: return
        val active = runtime.isActive()
        val base = JobIcons.find(job.icon, project)
        val dot = statusDotColor(runtime.status)
        val icon: Icon? = when {
            pressed && holding -> AllIcons.Actions.Restart
            // A launcher shows what a click does: start, wait (loader) or stop; the dot keeps the status visible.
            job.isGroup -> when {
                runtime.groupLaunch != null || runtime.groupRestartPending -> AnimatedIcon.Default.INSTANCE
                active -> StatusBadgeIcon(AllIcons.Actions.Suspend, dot)
                else -> StatusBadgeIcon(AllIcons.Actions.Execute, dot)
            }
            // Pointing at a running job shows that a click stops it.
            active && (hovered || pressed) -> StatusBadgeIcon(AllIcons.Actions.Suspend, dot)
            base != null -> if (runtime.isBusy()) BusyBadgeIcon(base) else StatusBadgeIcon(base, dot)
            runtime.isBusy() -> AnimatedIcon.Default.INSTANCE
            else -> null
        }
        label.icon = icon
        label.text = if (job.iconOnly && icon != null) "" else job.compactLabel
        label.foreground = when {
            !job.enabled -> UIUtil.getContextHelpForeground()
            // With an icon the status is the dot; plain text labels are colored by status.
            base != null || job.isGroup -> UIUtil.getLabelForeground()
            else -> statusColor(runtime.status)
        }
        // The busy arc is drawn from the clock; repaint only while it is shown.
        if (icon is BusyBadgeIcon) spinner.start() else spinner.stop()
        val hint = when {
            active -> "Click to stop · hold to restart"
            job.isGroup -> "Click to start all"
            else -> "Click to run"
        }
        toolTipText = "<html>" + listOfNotNull(job.name, statusDescription(runtime)).joinToString(" · ") +
            "<br><small>$hint · right-click for actions</small></html>"
        revalidate()
        repaint()
    }

    private fun perform(restart: Boolean) {
        val project = project ?: return
        val job = job ?: return
        val runtimes = JobRuntimeManager.get(project)
        when {
            restart -> runtimes.restart(job)
            runtimes.runtime(job).isActive() -> runtimes.stop(job)
            else -> JobActions.get(project).runInteractive(job, DataManager.getInstance().getDataContext(this), anchor = this)
        }
    }

    private fun cancelPress() {
        if (!pressed && !holding) return
        longPress.stop()
        pressed = false
        holding = false
        render()
    }

    private fun isInside(event: MouseEvent): Boolean =
        contains(SwingUtilities.convertPoint(event.component, event.point, this))

    override fun removeNotify() {
        spinner.stop()
        longPress.stop()
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
        if (event.id == MouseEvent.MOUSE_PRESSED) {
            cancelPress()
            showMenu()
        }
        event.consume()
        return true
    }

    private fun showMenu() {
        val project = project ?: return
        val job = job ?: return
        JobActions.get(project).showJobMenu(job, this)
    }

    private fun setHovered(value: Boolean) {
        if (hovered == value) return
        hovered = value
        render()
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val g = graphics.create() as Graphics2D
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val arc = JBUI.CurrentTheme.Tree.ARC.get()
        if (hovered || pressed) {
            g.color = if (pressed) JBUI.CurrentTheme.ActionButton.pressedBackground() else JBUI.CurrentTheme.ActionButton.hoverBackground()
            g.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
        }
        g.color = JBUI.CurrentTheme.ActionButton.hoverBorder()
        g.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
        g.dispose()
    }

    private companion object {
        const val LONG_PRESS_MS = 500
    }
}

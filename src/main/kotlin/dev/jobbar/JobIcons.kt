package dev.jobbar

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.IconLoader
import com.intellij.util.IconUtil
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.SimpleListCellRenderer
import javax.swing.DefaultComboBoxModel
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Arc2D
import java.awt.BasicStroke
import com.intellij.ui.scale.JBUIScale
import javax.swing.Icon

/**
 * Icons a job can show in toolbars and the job list. [JobDefinition.icon] holds either a key from [all]
 * or "file:<path>" for a custom SVG/PNG ($PROJECT_DIR$ allowed in the path).
 */
object JobIcons {
    private const val CUSTOM_PREFIX = "file:"
    // Combo entry that opens the file chooser instead of selecting an icon.
    private const val CHOOSE_CUSTOM = "\u0000choose"
    private const val SIZE = 16

    private fun bundled(name: String): Icon = IconLoader.getIcon("/icons/jobbar/$name.svg", JobIcons::class.java)

    val all: List<Pair<String, Icon>> = listOf(
        "angular" to bundled("angular"),
        "react" to bundled("react"),
        "vue" to bundled("vue"),
        "node" to bundled("node"),
        "npm" to bundled("npm"),
        "yarn" to bundled("yarn"),
        "docker" to bundled("docker"),
        "mock" to bundled("mock"),
        "translate" to bundled("translate"),
        "api" to bundled("api"),
        "server" to bundled("server"),
        "deploy" to bundled("rocket"),
        "power" to bundled("power"),
        "git" to bundled("git"),
        "check" to bundled("check"),
        "terminal" to bundled("terminal"),
        "styles" to bundled("palette"),
        "cloud" to bundled("cloud"),
        "mail" to bundled("mail"),
        "run" to AllIcons.Actions.Execute,
        "debug" to AllIcons.Actions.StartDebugger,
        "restart" to AllIcons.Actions.Restart,
        "refresh" to AllIcons.Actions.Refresh,
        "build" to AllIcons.Actions.Compile,
        "lightning" to AllIcons.Actions.Lightning,
        "download" to AllIcons.Actions.Download,
        "upload" to AllIcons.Actions.Upload,
        "web" to AllIcons.General.Web,
        "console" to AllIcons.Debugger.Console,
        "database" to AllIcons.Nodes.DataTables,
        "folder" to AllIcons.Nodes.Folder,
        "settings" to AllIcons.General.Settings,
        "search" to AllIcons.Actions.Search,
        "kill" to AllIcons.Actions.Suspend,
        "clean" to AllIcons.Actions.GC,
        "plugin" to AllIcons.Nodes.Plugin,
        "json" to AllIcons.FileTypes.Json,
        "html" to AllIcons.FileTypes.Html,
        "test" to AllIcons.Nodes.Test,
        "bookmark" to AllIcons.Nodes.Bookmark,
        "user" to AllIcons.General.User,
        "locate" to AllIcons.General.Locate,
        "pin" to AllIcons.General.Pin
    )

    // Custom icons by resolved path, reloaded when the file changes.
    private val customCache = ConcurrentHashMap<String, Pair<Long, Icon?>>()

    fun find(key: String, project: Project?): Icon? {
        if (!key.startsWith(CUSTOM_PREFIX)) return all.firstOrNull { it.first == key }?.second
        val file = File(key.removePrefix(CUSTOM_PREFIX).replace("\$PROJECT_DIR\$", project?.basePath.orEmpty()))
        if (!file.isFile) return null
        val modified = file.lastModified()
        customCache[file.path]?.takeIf { it.first == modified }?.let { return it.second }
        val icon = runCatching { IconLoader.findIcon(file.toURI().toURL(), false) }.getOrNull()
            // Custom files can be any size; toolbar labels need a standard 16 px icon.
            ?.let { if (it.iconWidth == SIZE && it.iconHeight == SIZE) it else IconUtil.toSize(it, JBUI.scale(SIZE), JBUI.scale(SIZE)) }
        customCache[file.path] = modified to icon
        return icon
    }

    private fun label(key: String): String = when {
        key.isEmpty() -> "None"
        key == CHOOSE_CUSTOM -> "Custom file…"
        key.startsWith(CUSTOM_PREFIX) -> File(key.removePrefix(CUSTOM_PREFIX)).name
        else -> key
    }

    /** Icon picker: "None", the built-in icons, the current custom file (if any) and "Custom file…". */
    fun comboBox(selected: String, project: Project): ComboBox<String> {
        val keys = buildList {
            add("")
            all.forEach { add(it.first) }
            if (selected.startsWith(CUSTOM_PREFIX)) add(selected)
            add(CHOOSE_CUSTOM)
        }
        val model = DefaultComboBoxModel(keys.toTypedArray())
        return ComboBox(model).apply {
            renderer = SimpleListCellRenderer.create { label, key, _ ->
                label.icon = key?.let { find(it, project) }
                label.text = label(key.orEmpty())
            }
            selectedItem = if (selected.isEmpty() || selected in keys) selected else ""
            var previous = selectedItem
            addActionListener {
                if (selectedItem != CHOOSE_CUSTOM) {
                    previous = selectedItem
                    return@addActionListener
                }
                val key = runCatching { chooseFile(this, "Choose Icon", project.basePath, "Icons", listOf("svg", "png")) }
                    .onFailure { Messages.showErrorDialog(this, it.message ?: "Unable to use this file", "Choose Icon") }
                    .getOrNull()
                    ?.let { customKey(it.toString(), project) }
                    ?.takeIf { find(it, project) != null }
                if (key == null) {
                    selectedItem = previous
                    return@addActionListener
                }
                if (model.getIndexOf(key) < 0) model.insertElementAt(key, model.size - 1)
                selectedItem = key
            }
        }
    }

    /** Stores paths inside the project relative to it, so exported jobs keep working in other checkouts. */
    private fun customKey(path: String, project: Project): String {
        val base = project.basePath
        val stored = if (base != null && path.startsWith("$base/")) "\$PROJECT_DIR\$/" + path.removePrefix("$base/") else path
        return CUSTOM_PREFIX + stored
    }

    /** The key to save: never the "Custom file…" entry. */
    fun selectedKey(comboBox: ComboBox<String>): String = (comboBox.selectedItem as? String)?.takeIf { it != CHOOSE_CUSTOM }.orEmpty()
}

/** [base] with a small bright status dot in its bottom-right corner (no dot when [dotColor] is null). */
class StatusBadgeIcon(private val base: Icon, private val dotColor: Color?) : Icon {
    override fun getIconWidth(): Int = base.iconWidth
    override fun getIconHeight(): Int = base.iconHeight

    override fun paintIcon(component: Component?, graphics: Graphics, x: Int, y: Int) {
        base.paintIcon(component, graphics, x, y)
        val color = dotColor ?: return
        val g = graphics.create() as Graphics2D
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        val dot = JBUIScale.scale(3.5f)
        val ring = JBUIScale.scale(1f)
        val outer = dot + ring * 2
        // Kept inside the icon bounds so toolbars never clip it.
        val left = x + iconWidth - outer
        val top = y + iconHeight - outer
        // The ring takes the color of what is behind the icon, cutting the dot out of the glyph.
        g.color = backgroundBehind(component)
        g.fill(Ellipse2D.Float(left, top, outer, outer))
        g.color = color
        g.fill(Ellipse2D.Float(left + ring, top + ring, dot, dot))
        g.dispose()
    }

    private fun backgroundBehind(component: Component?): Color =
        generateSequence(component) { it.parent }.firstOrNull { it.isOpaque }?.background ?: JBColor.background()
}

/** [base] with a small spinning arc in its bottom-right corner, where the status dot sits otherwise. */
class BusyBadgeIcon(private val base: Icon) : Icon {
    override fun getIconWidth(): Int = base.iconWidth
    override fun getIconHeight(): Int = base.iconHeight

    override fun paintIcon(component: Component?, graphics: Graphics, x: Int, y: Int) {
        base.paintIcon(component, graphics, x, y)
        val g = graphics.create() as Graphics2D
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        val size = JBUIScale.scale(6f)
        val stroke = JBUIScale.scale(1.3f)
        val gap = JBUIScale.scale(1f)
        val outer = size + gap * 2
        val left = x + iconWidth - outer
        val top = y + iconHeight - outer
        // Cut-out in the background color, like the status dot, so the arc does not blend into the glyph.
        g.color = generateSequence(component) { it.parent }.firstOrNull { it.isOpaque }?.background ?: JBColor.background()
        g.fill(Ellipse2D.Float(left, top, outer, outer))
        // One turn per second; the chip repaints while the job is busy.
        val angle = -((System.currentTimeMillis() % 1000) * 360 / 1000).toDouble()
        g.color = BUSY_COLOR
        g.stroke = BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        val inset = gap + stroke / 2
        g.draw(Arc2D.Float(left + inset, top + inset, outer - inset * 2, outer - inset * 2, angle.toFloat(), 270f, Arc2D.OPEN))
        g.dispose()
    }

    private companion object {
        val BUSY_COLOR = JBColor(Color(0x3574F0), Color(0x6EA2FF))
    }
}

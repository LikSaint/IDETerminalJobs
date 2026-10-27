package dev.jobbar

import com.intellij.icons.AllIcons
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
import javax.swing.Icon

/** Icons a job can show in toolbars and the job list; stored by key in [JobDefinition.icon]. */
object JobIcons {
    val all: List<Pair<String, Icon>> = listOf(
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

    fun find(key: String): Icon? = all.firstOrNull { it.first == key }?.second

    /** Icon picker; the first entry ("") means no icon. */
    fun comboBox(selected: String): ComboBox<String> =
        ComboBox(DefaultComboBoxModel((listOf("") + all.map { it.first }).toTypedArray())).apply {
            renderer = SimpleListCellRenderer.create { label, key, _ ->
                label.icon = find(key.orEmpty())
                label.text = key.orEmpty().ifEmpty { "None" }
            }
            selectedItem = selected.takeIf { find(it) != null } ?: ""
        }
}

/** [base] with a bright status dot in its bottom-left corner (no dot when [dotColor] is null). */
class StatusBadgeIcon(private val base: Icon, private val dotColor: Color?) : Icon {
    override fun getIconWidth(): Int = base.iconWidth
    override fun getIconHeight(): Int = base.iconHeight

    override fun paintIcon(component: Component?, graphics: Graphics, x: Int, y: Int) {
        base.paintIcon(component, graphics, x, y)
        val color = dotColor ?: return
        val g = graphics.create() as Graphics2D
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val size = JBUI.scale(7)
        val ring = JBUI.scale(1)
        val dotX = x - ring
        val dotY = y + iconHeight - size + ring
        // A ring in the background color keeps the dot readable over the icon.
        g.color = component?.background ?: JBColor.background()
        g.fillOval(dotX - ring, dotY - ring, size + ring * 2, size + ring * 2)
        g.color = color
        g.fillOval(dotX, dotY, size, size)
        g.dispose()
    }
}

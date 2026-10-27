package dev.jobbar

import com.jetbrains.JBRFileDialog
import java.awt.Component
import java.awt.Dialog
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities

/**
 * Picks an existing file with the native dialog and returns a real path.
 *
 * The platform chooser resolves the selection through VFS and the descriptor, which rejected valid files
 * ("unable to locate"). The IDE also runs with -Dapple.awt.fileDialogForDirectories=true, which turns a plain
 * macOS FileDialog into a folder picker (all files greyed out); like the platform's own native chooser, file
 * selection is requested explicitly through JBR hints. No FilenameFilter: the extension is checked afterwards.
 */
fun chooseFile(parent: Component?, title: String, directory: String?, filterName: String, extensions: List<String>): Path? {
    val dialog = when (val owner = parent?.let { SwingUtilities.getWindowAncestor(it) }) {
        is Dialog -> FileDialog(owner, title, FileDialog.LOAD)
        else -> FileDialog(owner as? Frame, title, FileDialog.LOAD)
    }.apply {
        this.directory = directory
        JBRFileDialog.get(this)?.apply {
            hints = JBRFileDialog.SELECT_FILES_HINT
            setFileFilterExtensions(filterName, extensions.toTypedArray())
        }
        isVisible = true
    }
    val path = dialog.files.firstOrNull()?.toPath() ?: return null
    require(Files.isRegularFile(path)) { "The selected file does not exist: $path" }
    require(extensions.any { path.fileName.toString().endsWith(".$it", ignoreCase = true) }) {
        "Please choose a ${extensions.joinToString("/") { ".$it" }} file: $path"
    }
    return path
}

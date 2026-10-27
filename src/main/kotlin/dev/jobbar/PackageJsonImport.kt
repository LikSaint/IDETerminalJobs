package dev.jobbar

import com.google.gson.JsonParser
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.table.AbstractTableModel
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText

/** A script found in a package.json, with the job it would become. */
data class ScriptCandidate(val folder: String, val script: String, val body: String, val job: JobDefinition, var selected: Boolean)

object PackageJsonImport {
    private val skippedDirs = setOf("node_modules", ".git", ".idea", "dist", "build", "out", ".angular", ".next", "coverage")

    fun scan(project: Project, existing: List<JobDefinition>): List<ScriptCandidate> {
        val root = project.basePath?.let(Path::of) ?: return emptyList()
        return findPackageJsons(root).flatMap { file ->
            val dir = file.parent
            val folder = root.relativize(dir).toString()
            val scripts = runCatching {
                JsonParser.parseString(file.readText()).asJsonObject.getAsJsonObject("scripts")
                    ?.entrySet()?.associate { it.key to it.value.asString }
            }.getOrNull().orEmpty()
            val manager = packageManager(dir, root)
            scripts.map { (script, body) ->
                val job = jobFor(script, body, manager, folder)
                val duplicate = existing.any { it.command == job.command && it.workingDirectory == job.workingDirectory }
                ScriptCandidate(folder, script, body, job, selected = false).also { if (duplicate) it.job.name += " (exists)" }
            }
        }
    }

    // package.json files up to a few levels deep, skipping dependency and build folders.
    private fun findPackageJsons(root: Path): List<Path> {
        val result = mutableListOf<Path>()
        fun visit(dir: Path, depth: Int) {
            dir.resolve("package.json").takeIf { it.exists() }?.let(result::add)
            if (depth >= 4) return
            runCatching { Files.list(dir).use { stream -> stream.toList() } }.getOrDefault(emptyList())
                .filter { it.isDirectory() && it.name !in skippedDirs && !it.name.startsWith(".") }
                .sorted()
                .forEach { visit(it, depth + 1) }
        }
        visit(root, 0)
        return result
    }

    /** Detected from the lock file next to package.json or at the project root. */
    private fun packageManager(dir: Path, root: Path): String {
        for (base in listOf(dir, root)) {
            when {
                base.resolve("yarn.lock").exists() -> return "yarn"
                base.resolve("pnpm-lock.yaml").exists() -> return "pnpm"
                base.resolve("bun.lockb").exists() || base.resolve("bun.lock").exists() -> return "bun"
                base.resolve("package-lock.json").exists() -> return "npm"
            }
        }
        return "npm"
    }

    private fun jobFor(script: String, body: String, manager: String, folder: String): JobDefinition {
        val command = when (manager) {
            "yarn" -> "yarn $script"
            "pnpm" -> "pnpm $script"
            "bun" -> "bun run $script"
            else -> "npm run $script"
        }
        val text = body.lowercase()
        val watching = Regex("""(^|[\s:_-])(start|serve|dev|watch|mock)([\s:_-]|$)""").containsMatchIn(script.lowercase()) ||
            listOf("--watch", " -w", "nodemon", "ng serve", "serve").any { it in text }
        val type = when {
            "ng serve" in text || ("ng " in text && watching) -> JobType.ANGULAR
            "vite" in text && "vite build" !in text -> JobType.VITE
            "tsc" in text -> JobType.TYPESCRIPT
            "jest" in text -> JobType.JEST
            "nodemon" in text || "ts-node" in text || "node " in text -> JobType.NODE
            else -> JobType.GENERIC
        }
        val preset = JobPresets.forType(type)
        return JobDefinition(
            name = if (folder.isEmpty()) script else "$script ($folder)",
            command = command,
            workingDirectory = if (folder.isEmpty()) "\$PROJECT_DIR\$" else "\$PROJECT_DIR\$/$folder",
            runMode = if (watching) JobRunMode.TRACKED else JobRunMode.ONE_SHOT,
            type = type,
            busyPatterns = preset.busy,
            errorPatterns = preset.errors,
            recoveryPatterns = preset.recovery
        )
    }
}

/** Lets the user pick which package.json scripts become jobs. */
class PackageJsonImportDialog(project: Project, private val candidates: List<ScriptCandidate>) : DialogWrapper(project) {
    private val model = object : AbstractTableModel() {
        private val columns = listOf("", "Script", "Command", "Mode", "Folder")
        override fun getRowCount(): Int = candidates.size
        override fun getColumnCount(): Int = columns.size
        override fun getColumnName(column: Int): String = columns[column]
        override fun getColumnClass(column: Int): Class<*> = if (column == 0) java.lang.Boolean::class.java else String::class.java
        override fun isCellEditable(row: Int, column: Int): Boolean = column == 0
        override fun getValueAt(row: Int, column: Int): Any = candidates[row].let {
            when (column) {
                0 -> it.selected
                1 -> it.script
                2 -> it.job.command
                3 -> it.job.runMode.label
                else -> it.folder.ifEmpty { "(root)" }
            }
        }
        override fun setValueAt(value: Any?, row: Int, column: Int) {
            if (column == 0) candidates[row].selected = value == true
        }
    }
    private val table = JBTable(model).apply {
        columnModel.getColumn(0).maxWidth = JBUI.scale(32)
    }

    init {
        title = "Add Jobs from package.json"
        init()
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
        preferredSize = Dimension(JBUI.scale(720), JBUI.scale(420))
        add(JBLabel("Pick the scripts to add as jobs. Mode and status patterns are guessed from the script; adjust them later in Edit Job."), BorderLayout.NORTH)
        add(JBScrollPane(table), BorderLayout.CENTER)
    }

    fun selectedJobs(): List<JobDefinition> = candidates.filter { it.selected }.map { candidate ->
        candidate.job.copyDeep().apply { name = name.removeSuffix(" (exists)") }
    }
}

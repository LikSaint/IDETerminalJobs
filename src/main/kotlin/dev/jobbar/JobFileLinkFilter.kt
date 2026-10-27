package dev.jobbar

import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File

/**
 * Turns file references in job output into links, e.g. `src/app/foo.component.ts:12:5`,
 * `./src/main.ts(3,1)` or absolute paths. Relative paths resolve against the job's working directory.
 */
class JobFileLinkFilter(private val project: Project, private val baseDir: File) : Filter, DumbAware {
    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        val lineStart = entireLength - line.length
        val items = pathPattern.findAll(line).mapNotNull { match ->
            val path = match.groups["path"]?.value ?: return@mapNotNull null
            val file = (if (File(path).isAbsolute) File(path) else File(baseDir, path.removePrefix("./")))
            val virtualFile = LocalFileSystem.getInstance().findFileByIoFile(file)?.takeIf { !it.isDirectory } ?: return@mapNotNull null
            val lineNumber = (match.groups["line"] ?: match.groups["pline"])?.value?.toIntOrNull()?.minus(1) ?: 0
            val column = (match.groups["col"] ?: match.groups["pcol"])?.value?.toIntOrNull()?.minus(1) ?: 0
            Filter.ResultItem(
                lineStart + match.range.first,
                lineStart + match.range.last + 1,
                OpenFileHyperlinkInfo(project, virtualFile, lineNumber, column)
            )
        }.toList()
        return if (items.isEmpty()) null else Filter.Result(items)
    }

    private companion object {
        // A path with a source-like extension, optionally followed by :line[:col] or (line,col).
        val pathPattern = Regex(
            """(?<![\w/.@-])(?<path>(?:/|\./|\.\./)?[\w.@-]+(?:/[\w.@-]+)*\.(?:ts|tsx|js|jsx|mjs|cjs|vue|html|scss|sass|less|css|json|ya?ml|md|kt|java|py))""" +
                """(?::(?<line>\d+)(?::(?<col>\d+))?|\((?<pline>\d+),(?<pcol>\d+)\))?"""
        )
    }
}

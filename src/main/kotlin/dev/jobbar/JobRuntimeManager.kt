package dev.jobbar

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.ui.ConsoleView
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.swing.Icon
import javax.swing.SwingUtilities

enum class JobRuntimeStatus {
    IDLE, STARTING, RUNNING, READY, WARNING, SUCCESS, STOPPED, ERROR
}

data class JobRuntime(
    var status: JobRuntimeStatus = JobRuntimeStatus.IDLE,
    var handler: KillableColoredProcessHandler? = null,
    var console: ConsoleView? = null,
    var content: Content? = null,
    var stoppedByUser: Boolean = false,
    var lastCommand: String = "",
    var lastVariant: JobVariant? = null,
    var scanBuffer: String = "",
    var cycleHasError: Boolean = false,
    // "Run all": the variants to go through and the index of the one currently running (-1 = none).
    var sequence: List<JobVariant> = emptyList(),
    var sequenceIndex: Int = -1
) {
    val inSequence: Boolean get() = sequenceIndex in sequence.indices

    fun clearSequence() {
        sequence = emptyList()
        sequenceIndex = -1
    }
}

@Service(Service.Level.PROJECT)
class JobRuntimeManager(private val project: Project) : Disposable {
    private val runtimes = mutableMapOf<String, JobRuntime>()
    private val listeners = mutableListOf<() -> Unit>()
    private var activeContent: Content? = null
    private val ansi = Regex("\\u001B(?:\\[[0-?]*[ -/]*[@-~]|\\][^\\u0007]*(?:\\u0007|\\u001B\\\\))")

    fun runtime(job: JobDefinition): JobRuntime = runtimes.getOrPut(job.id) { JobRuntime() }
    fun addListener(listener: () -> Unit) { listeners += listener }

    fun run(job: JobDefinition, variant: JobVariant? = null) = start(job, variant, continueSequence = false)

    /** Runs every variant in order; the next one starts only after the previous one succeeds. */
    fun runAll(job: JobDefinition) {
        val runtime = runtime(job)
        if (job.variants.isEmpty() || isProcessAlive(runtime)) return
        runtime.sequence = job.variants.map { it.copy() }
        runtime.sequenceIndex = 0
        start(job, runtime.sequence.first(), continueSequence = true)
    }

    private fun isProcessAlive(runtime: JobRuntime): Boolean =
        runtime.handler?.isProcessTerminating == false && runtime.handler?.isProcessTerminated == false

    private fun start(job: JobDefinition, variant: JobVariant?, continueSequence: Boolean) {
        val runtime = runtime(job)
        if (isProcessAlive(runtime)) return
        if (!continueSequence) runtime.clearSequence()

        val command = resolveCommand(job, variant)
        val console = prepareConsole(job, runtime)
        runtime.status = JobRuntimeStatus.STARTING
        runtime.stoppedByUser = false
        runtime.lastCommand = command
        runtime.lastVariant = variant
        runtime.scanBuffer = ""
        runtime.cycleHasError = false
        val progress = if (runtime.inSequence) "[${runtime.sequenceIndex + 1}/${runtime.sequence.size}] " else ""
        console.print("\n▶ ${LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))}  $progress$command\n", com.intellij.execution.ui.ConsoleViewContentType.SYSTEM_OUTPUT)
        fireChanged()

        try {
            val workingDirectory = job.workingDirectory
                .replace("\$PROJECT_DIR\$", project.basePath.orEmpty())
                .let(::File)
            val shell = if (SystemInfo.isWindows) listOf("cmd.exe", "/c", command) else listOf("/bin/zsh", "-lc", command)
            val commandLine = GeneralCommandLine(shell).withWorkDirectory(workingDirectory)
            val handler = KillableColoredProcessHandler(commandLine)
            runtime.handler = handler
            console.attachToProcess(handler)
            handler.addProcessListener(object : ProcessListener {
                override fun startNotified(event: ProcessEvent) {
                    runtime.status = when {
                        job.runMode == JobRunMode.ONE_SHOT -> JobRuntimeStatus.RUNNING
                        job.recoveryPatterns.isBlank() -> JobRuntimeStatus.READY
                        else -> JobRuntimeStatus.STARTING
                    }
                    fireChanged()
                }

                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    val cleanText = ansi.replace(event.text, "")
                    inspect(job, runtime, cleanText)
                }

                override fun processTerminated(event: ProcessEvent) {
                    runtime.status = when {
                        runtime.stoppedByUser -> JobRuntimeStatus.STOPPED
                        event.exitCode != 0 -> JobRuntimeStatus.ERROR
                        // Tools like yarn can print an error and still exit with 0.
                        job.runMode == JobRunMode.ONE_SHOT && runtime.cycleHasError -> JobRuntimeStatus.ERROR
                        job.runMode == JobRunMode.ONE_SHOT -> JobRuntimeStatus.SUCCESS
                        else -> JobRuntimeStatus.STOPPED
                    }
                    if (runtime.inSequence) advanceSequence(job, runtime, console)
                    fireChanged()
                }
            })
            handler.startNotify()
        } catch (error: Exception) {
            runtime.status = JobRuntimeStatus.ERROR
            console.print("${error.message}\n", com.intellij.execution.ui.ConsoleViewContentType.ERROR_OUTPUT)
            if (runtime.inSequence) advanceSequence(job, runtime, console)
            fireChanged()
        }
    }

    private fun advanceSequence(job: JobDefinition, runtime: JobRuntime, console: ConsoleView) {
        val index = runtime.sequenceIndex
        val total = runtime.sequence.size
        val current = runtime.sequence[index]
        when {
            runtime.status == JobRuntimeStatus.STOPPED -> {
                console.print("\n■ Run all stopped at “${current.label}” (${index + 1}/$total)\n", com.intellij.execution.ui.ConsoleViewContentType.SYSTEM_OUTPUT)
                runtime.clearSequence()
            }
            runtime.status != JobRuntimeStatus.SUCCESS -> {
                console.print("\n✖ Run all failed at “${current.label}” (${index + 1}/$total); ${total - index - 1} remaining skipped\n", com.intellij.execution.ui.ConsoleViewContentType.ERROR_OUTPUT)
                runtime.clearSequence()
            }
            index + 1 >= total -> {
                console.print("\n✔ Run all completed: $total/$total succeeded\n", com.intellij.execution.ui.ConsoleViewContentType.SYSTEM_OUTPUT)
                runtime.clearSequence()
            }
            else -> {
                runtime.sequenceIndex = index + 1
                // Keep the loader on between variants.
                runtime.status = JobRuntimeStatus.RUNNING
                val next = runtime.sequence[index + 1]
                SwingUtilities.invokeLater {
                    // Skip if the sequence was stopped or replaced in the meantime.
                    if (runtime.sequenceIndex == index + 1 && runtime.sequence.getOrNull(index + 1) === next) {
                        start(job, next, continueSequence = true)
                    }
                }
            }
        }
    }

    fun stop(job: JobDefinition) {
        val runtime = runtime(job)
        if (runtime.inSequence && !isProcessAlive(runtime)) {
            // Stopped between two variants: there is no process to kill.
            runtime.clearSequence()
            runtime.status = JobRuntimeStatus.STOPPED
            fireChanged()
            return
        }
        runtime.stoppedByUser = true
        runtime.handler?.destroyProcess()
    }

    fun restart(job: JobDefinition) {
        val runtime = runtime(job)
        // Repeat the variant that was last run, so its parameter is substituted again.
        val variant = runtime.lastVariant
        val handler = runtime.handler
        if (handler != null && !handler.isProcessTerminated) {
            runtime.stoppedByUser = true
            handler.addProcessListener(object : ProcessListener {
                override fun processTerminated(event: ProcessEvent) {
                    SwingUtilities.invokeLater { run(job, variant) }
                }
            })
            handler.destroyProcess()
        } else run(job, variant)
    }

    fun openConsole(job: JobDefinition) {
        val runtime = runtime(job)
        if (runtime.console == null) prepareConsole(job, runtime)
        else runtime.content?.let { showOnly(it) }
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Job Bar") ?: return
        toolWindow.show {
            runtime.content?.let { toolWindow.contentManager.setSelectedContent(it, true) }
        }
    }

    private fun prepareConsole(job: JobDefinition, runtime: JobRuntime): ConsoleView {
        if (job.reuseConsole && runtime.console != null) {
            runtime.content?.let { showOnly(it) }
            return runtime.console!!
        }
        runtime.content?.let { old -> removeContent(old) }
        val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
        val content = ContentFactory.getInstance().createContent(console.component, job.name, false).apply {
            icon = AllIcons.Toolwindows.ToolWindowRun
            isCloseable = false
        }
        runtime.console = console
        runtime.content = content
        showOnly(content)
        Disposer.register(this, console)
        return console
    }

    private fun showOnly(content: Content) {
        val manager = ToolWindowManager.getInstance(project).getToolWindow("Job Bar")?.contentManager ?: return
        activeContent?.takeIf { it !== content }?.let { removeContent(it) }
        if (!manager.contents.contains(content)) manager.addContent(content)
        activeContent = content
    }

    private fun removeContent(content: Content) {
        ToolWindowManager.getInstance(project).getToolWindow("Job Bar")?.contentManager?.removeContent(content, false)
        if (activeContent === content) activeContent = null
    }

    private fun resolveCommand(job: JobDefinition, variant: JobVariant?): String {
        if (variant == null) return job.command
        val placeholder = "\${${job.parameterName}}"
        return if (job.parameterName.isNotBlank() && job.command.contains(placeholder)) {
            job.command.replace(placeholder, variant.value)
        } else "${job.command} ${variant.value}".trim()
    }

    private fun inspect(job: JobDefinition, runtime: JobRuntime, text: String) {
        val prefixLength = runtime.scanBuffer.length
        val fragment = runtime.scanBuffer + text
        val events = buildList {
            lastNewMatchIndex(job.errorPatterns, fragment, prefixLength).takeIf { it >= 0 }?.let { add(StatusEvent(it, StatusEventType.ERROR)) }
            lastNewMatchIndex(job.recoveryPatterns, fragment, prefixLength).takeIf { it >= 0 }?.let { add(StatusEvent(it, StatusEventType.RECOVERY)) }
            lastNewMatchIndex(job.busyPatterns, fragment, prefixLength).takeIf { it >= 0 }?.let { add(StatusEvent(it, StatusEventType.BUSY)) }
        }.sortedWith(compareBy<StatusEvent> { it.index }.thenBy { it.type.priority })

        runtime.scanBuffer = fragment.takeLast(1_024)
        if (job.runMode == JobRunMode.ONE_SHOT) {
            // One-shot jobs keep their running status; patterns only decide the final result.
            events.forEach { event -> runtime.cycleHasError = event.type == StatusEventType.ERROR }
            return
        }

        var changed = false
        events.forEach { event ->
            when (event.type) {
                StatusEventType.BUSY -> {
                    runtime.cycleHasError = false
                    if (runtime.status != JobRuntimeStatus.STARTING) {
                        runtime.status = JobRuntimeStatus.STARTING
                        changed = true
                    }
                }
                StatusEventType.ERROR -> {
                    runtime.cycleHasError = true
                    if (runtime.status != JobRuntimeStatus.WARNING) {
                        runtime.status = JobRuntimeStatus.WARNING
                        changed = true
                    }
                }
                StatusEventType.RECOVERY -> {
                    val completedStatus = if (runtime.cycleHasError) JobRuntimeStatus.WARNING else JobRuntimeStatus.READY
                    runtime.cycleHasError = false
                    if (runtime.status != completedStatus) {
                        runtime.status = completedStatus
                        changed = true
                    }
                }
            }
        }
        if (changed) fireChanged()
    }

    private fun lastNewMatchIndex(patterns: String, text: String, prefixLength: Int): Int = patterns.lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .maxOfOrNull { pattern ->
            if (pattern.startsWith("regex:")) {
                runCatching {
                    Regex(pattern.removePrefix("regex:"), RegexOption.IGNORE_CASE)
                        .findAll(text)
                        .filter { it.range.last >= prefixLength }
                        .lastOrNull()?.range?.first ?: -1
                }.getOrDefault(-1)
            } else {
                text.lastIndexOf(pattern, ignoreCase = true)
                    .takeIf { it >= 0 && it + pattern.length > prefixLength } ?: -1
            }
        } ?: -1

    private data class StatusEvent(val index: Int, val type: StatusEventType)

    private enum class StatusEventType(val priority: Int) {
        ERROR(0),
        RECOVERY(1),
        BUSY(2)
    }

    private fun fireChanged() = SwingUtilities.invokeLater { listeners.toList().forEach { it() } }

    override fun dispose() {
        runtimes.values.forEach {
            it.stoppedByUser = true
            it.handler?.destroyProcess()
        }
        runtimes.clear()
    }

    companion object {
        fun get(project: Project): JobRuntimeManager = project.service()
    }
}

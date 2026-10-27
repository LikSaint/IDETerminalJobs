package dev.jobbar

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.project.DumbAwareAction
import java.awt.BorderLayout
import javax.swing.JPanel
import com.intellij.ide.ActivityTracker
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.concurrency.AppExecutorUtil
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
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
    // The job's tab in the platform Run tool window, and the tab it replaces on the next show.
    var descriptor: RunContentDescriptor? = null,
    var replacedDescriptor: RunContentDescriptor? = null,
    var stoppedByUser: Boolean = false,
    var lastCommand: String = "",
    var lastVariant: JobVariant? = null,
    var scanBuffer: String = "",
    var cycleHasError: Boolean = false,
    // "Run all": the variants to go through and the index of the one currently running (-1 = none).
    var sequence: List<JobVariant> = emptyList(),
    var sequenceIndex: Int = -1,
    // Port / URL readiness polling of the current process.
    var readyCheck: ScheduledFuture<*>? = null,
    // Sounds: the status last reacted to, and whether this run has already been ready once.
    var notifiedStatus: JobRuntimeStatus = JobRuntimeStatus.IDLE,
    var readyNotified: Boolean = false,
    // Groups: the members being launched one after another (null = no launch in progress).
    var groupLaunch: GroupLaunch? = null,
    var groupRestartPending: Boolean = false,
    var groupSummary: String = ""
) {
    val inSequence: Boolean get() = sequenceIndex in sequence.indices

    fun clearSequence() {
        sequence = emptyList()
        sequenceIndex = -1
    }
}

/** A group launch in progress: [index] is the member being started or waited for. */
class GroupLaunch(val members: List<GroupMember>, val sequential: Boolean) {
    var index = 0
}

@Service(Service.Level.PROJECT)
class JobRuntimeManager(private val project: Project) : Disposable {
    private val runtimes = mutableMapOf<String, JobRuntime>()
    private val listeners = mutableListOf<() -> Unit>()
    private val ansi = Regex("\\u001B(?:\\[[0-?]*[ -/]*[@-~]|\\][^\\u0007]*(?:\\u0007|\\u001B\\\\))")

    fun runtime(job: JobDefinition): JobRuntime = runtimes.getOrPut(job.id) { JobRuntime() }
    fun addListener(listener: () -> Unit) { listeners += listener }

    fun run(job: JobDefinition, variant: JobVariant? = null) {
        if (job.isGroup) runGroup(job) else start(job, variant, continueSequence = false)
    }

    /** Runs every variant in order; the next one starts only after the previous one succeeds. */
    fun runAll(job: JobDefinition) {
        val runtime = runtime(job)
        if (!job.enabled || job.variants.isEmpty() || isProcessAlive(runtime)) return
        runtime.sequence = job.variants.map { it.copy() }
        runtime.sequenceIndex = 0
        start(job, runtime.sequence.first(), continueSequence = true)
    }

    private fun isProcessAlive(runtime: JobRuntime): Boolean =
        runtime.handler?.isProcessTerminating == false && runtime.handler?.isProcessTerminated == false

    private fun start(job: JobDefinition, variant: JobVariant?, continueSequence: Boolean) {
        val runtime = runtime(job)
        if (!job.enabled || job.isGroup || isProcessAlive(runtime)) return
        if (!continueSequence) runtime.clearSequence()
        runtime.readyNotified = false

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
            val workingDirectory = workingDirectory(job)
            val shell = if (SystemInfo.isWindows) listOf("cmd.exe", "/c", command) else listOf("/bin/zsh", "-lc", command)
            val commandLine = GeneralCommandLine(shell).withWorkDirectory(workingDirectory)
            val handler = KillableColoredProcessHandler(commandLine)
            runtime.handler = handler
            runtime.descriptor?.processHandler = handler
            console.attachToProcess(handler)
            handler.addProcessListener(object : ProcessListener {
                override fun startNotified(event: ProcessEvent) {
                    runtime.status = when {
                        job.runMode == JobRunMode.ONE_SHOT -> JobRuntimeStatus.RUNNING
                        job.recoveryPatterns.isBlank() && !job.hasReadyCheck -> JobRuntimeStatus.READY
                        else -> JobRuntimeStatus.STARTING
                    }
                    if (job.runMode == JobRunMode.TRACKED && job.hasReadyCheck) startReadyCheck(job, runtime, handler)
                    fireChanged()
                }

                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    val cleanText = ansi.replace(event.text, "")
                    inspect(job, runtime, cleanText)
                }

                override fun processTerminated(event: ProcessEvent) {
                    runtime.readyCheck?.cancel(false)
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
            showRunTab(runtime, bringToFront = false)
            handler.startNotify()
        } catch (error: Exception) {
            runtime.status = JobRuntimeStatus.ERROR
            showRunTab(runtime, bringToFront = false)
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
        if (job.isGroup) return stopGroup(job)
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
        if (job.isGroup) return restartGroup(job)
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
        if (job.isGroup) {
            // A group has no output of its own: add the members' tabs and show the first one.
            val members = groupJobs(job)
            members.drop(1).forEach { member ->
                val memberRuntime = runtime(member)
                if (memberRuntime.descriptor?.let(::isShown) != true) prepareConsole(member, memberRuntime)
                showRunTab(memberRuntime, bringToFront = false)
            }
            members.firstOrNull()?.let(::openConsole)
            return
        }
        val runtime = runtime(job)
        if (runtime.descriptor?.let(::isShown) != true) prepareConsole(job, runtime)
        showRunTab(runtime, bringToFront = true)
    }

    private fun workingDirectory(job: JobDefinition): File =
        File(job.workingDirectory.replace("\$PROJECT_DIR\$", project.basePath.orEmpty()))

    /** Returns the console for this run: the job's existing Run tab when it reuses its console, otherwise a new tab. */
    private fun prepareConsole(job: JobDefinition, runtime: JobRuntime): ConsoleView {
        val current = runtime.descriptor?.takeIf(::isShown)
        if (job.reuseConsole && current != null) return runtime.console!!

        val console = TextConsoleBuilderFactory.getInstance().createBuilder(project)
            .apply { addFilter(JobFileLinkFilter(project, workingDirectory(job))) }
            .console
        val panel = JPanel(BorderLayout()).apply { add(console.component, BorderLayout.CENTER) }
        val actions = DefaultActionGroup(RerunAction(job.id), StopAction(job.id), Separator.getInstance(), *console.createConsoleActions())
        ActionManager.getInstance().createActionToolbar("JobBar.RunTab", actions, false).apply {
            targetComponent = console.component
            panel.add(component, BorderLayout.WEST)
        }
        // The descriptor owns and disposes the console when its tab is closed or replaced.
        runtime.descriptor = RunContentDescriptor(console, null, panel, job.name, AllIcons.Actions.Execute)
        runtime.replacedDescriptor = current
        runtime.console = console
        return console
    }

    /**
     * Runs happen in the background: the tab is added to the Run tool window without opening it or
     * switching tabs. Only Open Console ([bringToFront]) shows the tool window and selects the tab.
     */
    private fun showRunTab(runtime: JobRuntime, bringToFront: Boolean) {
        val descriptor = runtime.descriptor ?: return
        val manager = RunContentManager.getInstance(project)
        val executor = DefaultRunExecutor.getRunExecutorInstance()
        if (!isShown(descriptor)) {
            descriptor.isActivateToolWindowWhenAdded = bringToFront
            descriptor.isSelectContentWhenAdded = bringToFront
            descriptor.isAutoFocusContent = bringToFront
            manager.showRunContent(executor, descriptor, runtime.replacedDescriptor)
            runtime.replacedDescriptor = null
        }
        if (bringToFront) {
            manager.toFrontRunContent(executor, descriptor)
            manager.getToolWindowByDescriptor(descriptor)?.show()
        }
    }

    private fun isShown(descriptor: RunContentDescriptor): Boolean =
        RunContentManager.getInstance(project).allDescriptors.contains(descriptor)

    // Run tab actions look the job up by id, so they use its latest definition after edits.
    private fun jobById(id: String): JobDefinition? = JobBarService.get(project).jobs().find { it.id == id }

    private inner class RerunAction(private val jobId: String) :
        DumbAwareAction("Rerun", "Run the job again with the same variant", AllIcons.Actions.Restart) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) { e.presentation.isEnabled = jobById(jobId) != null }
        override fun actionPerformed(e: AnActionEvent) { jobById(jobId)?.let(::restart) }
    }

    private inner class StopAction(private val jobId: String) :
        DumbAwareAction("Stop", "Stop the job", AllIcons.Actions.Suspend) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) { e.presentation.isEnabled = runtimes[jobId]?.isActive() == true }
        override fun actionPerformed(e: AnActionEvent) { jobById(jobId)?.let(::stop) }
    }

    // ---- Readiness by port / URL --------------------------------------------------------------------------

    private fun startReadyCheck(job: JobDefinition, runtime: JobRuntime, handler: KillableColoredProcessHandler) {
        runtime.readyCheck?.cancel(false)
        runtime.readyCheck = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({
            if (runtime.handler !== handler || handler.isProcessTerminated) {
                runtime.readyCheck?.cancel(false)
            } else if (isReady(job)) {
                runtime.readyCheck?.cancel(false)
                // Only promote a run that is still starting; error patterns keep their warning.
                if (runtime.status == JobRuntimeStatus.STARTING) {
                    runtime.status = JobRuntimeStatus.READY
                    fireChanged()
                }
            }
        }, 1, 1, TimeUnit.SECONDS)
    }

    private fun isReady(job: JobDefinition): Boolean {
        if (job.readyPort > 0) {
            val open = runCatching {
                Socket().use { it.connect(InetSocketAddress("localhost", job.readyPort), 300) }
            }.isSuccess
            if (open) return true
        }
        if (job.readyUrl.isNotBlank()) {
            // Any HTTP response means the server is up, whatever its status code.
            val responded = runCatching {
                (URI(job.readyUrl.trim()).toURL().openConnection() as HttpURLConnection).run {
                    connectTimeout = 1_000
                    readTimeout = 1_000
                    requestMethod = "GET"
                    try { responseCode } finally { disconnect() }
                }
            }.isSuccess
            if (responded) return true
        }
        return false
    }

    // ---- Groups ---------------------------------------------------------------------------------------------

    private fun groupJobs(group: JobDefinition): List<JobDefinition> =
        group.members.mapNotNull { jobById(it.jobId) }.filter { !it.isGroup }

    private fun runGroup(group: JobDefinition) {
        if (!group.enabled) return
        val runtime = runtime(group)
        if (runtime.groupLaunch != null) return
        val members = group.members.filter { member -> jobById(member.jobId)?.let { !it.isGroup && it.enabled } == true }
        if (members.isEmpty()) return
        val launch = GroupLaunch(members, group.groupSequential)
        runtime.groupLaunch = launch
        runtime.groupRestartPending = false
        runtime.readyNotified = false
        if (launch.sequential) startMember(members.first())
        else members.forEach(::startMember)
        fireChanged()
    }

    private fun startMember(member: GroupMember) {
        val job = jobById(member.jobId) ?: return
        // A member that is already running is not restarted; a sequential launch just waits for it to be ready.
        if (runtime(job).isActive()) return
        when (member.variant) {
            "" -> start(job, null, continueSequence = false)
            GroupMember.ALL_VARIANTS -> runAll(job)
            else -> start(job, job.variants.find { it.value == member.variant } ?: JobVariant(member.variant, member.variant), continueSequence = false)
        }
    }

    private fun stopGroup(group: JobDefinition) {
        val runtime = runtime(group)
        runtime.groupLaunch = null
        runtime.groupRestartPending = false
        groupJobs(group).filter { runtime(it).isActive() }.forEach(::stop)
        fireChanged()
    }

    private fun restartGroup(group: JobDefinition) {
        stopGroup(group)
        // The launch starts from refreshGroups() once every member process has ended.
        runtime(group).groupRestartPending = true
        fireChanged()
    }

    private fun memberDone(job: JobDefinition): Boolean {
        val runtime = runtime(job)
        return !runtime.isBusy() && when (job.runMode) {
            JobRunMode.ONE_SHOT -> runtime.status == JobRuntimeStatus.SUCCESS
            else -> runtime.status == JobRuntimeStatus.READY
        }
    }

    private fun memberFailed(job: JobDefinition): Boolean {
        val runtime = runtime(job)
        return !runtime.isBusy() && runtime.status in setOf(JobRuntimeStatus.ERROR, JobRuntimeStatus.STOPPED, JobRuntimeStatus.IDLE)
    }

    /** Drives group launches and recomputes group statuses from their members. Runs on the EDT. */
    private fun refreshGroups() {
        JobBarService.get(project).jobs().filter { it.isGroup }.forEach { group ->
            val runtime = runtime(group)
            val members = groupJobs(group)
            if (runtime.groupRestartPending && members.none { isProcessAlive(runtime(it)) }) {
                runtime.groupRestartPending = false
                runGroup(group)
            }
            runtime.groupLaunch?.let { launch -> advanceGroup(group, runtime, launch) }

            val statuses = members.map { runtime(it) }
            val ready = members.count(::memberDone)
            runtime.groupSummary = if (members.isEmpty()) "" else "$ready/${members.size} ready"
            runtime.status = when {
                runtime.groupLaunch != null || runtime.groupRestartPending || statuses.any { it.isBusy() } -> JobRuntimeStatus.RUNNING
                statuses.any { it.status == JobRuntimeStatus.ERROR } -> JobRuntimeStatus.ERROR
                statuses.any { it.status == JobRuntimeStatus.WARNING } -> JobRuntimeStatus.WARNING
                members.isNotEmpty() && ready == members.size ->
                    if (statuses.any { it.status == JobRuntimeStatus.READY }) JobRuntimeStatus.READY else JobRuntimeStatus.SUCCESS
                // Some members up, some not.
                statuses.any { it.status == JobRuntimeStatus.READY } -> JobRuntimeStatus.WARNING
                else -> JobRuntimeStatus.IDLE
            }
        }
    }

    private fun advanceGroup(group: JobDefinition, runtime: JobRuntime, launch: GroupLaunch) {
        val pending = launch.members.drop(launch.index).mapNotNull { member -> jobById(member.jobId)?.let { member to it } }
        if (!launch.sequential) {
            val jobs = launch.members.mapNotNull { jobById(it.jobId) }
            when {
                jobs.any(::memberFailed) -> finishGroup(runtime, success = false)
                jobs.all(::memberDone) -> finishGroup(runtime, success = true)
            }
            return
        }
        if (pending.isEmpty()) return finishGroup(runtime, success = true)
        // Move past every member that is already done (e.g. it was running before), starting the next ones.
        while (true) {
            val current = launch.members.getOrNull(launch.index)?.let { jobById(it.jobId) }
                ?: return finishGroup(runtime, success = true)
            when {
                memberFailed(current) -> return finishGroup(runtime, success = false)
                !memberDone(current) -> return
            }
            launch.index++
            launch.members.getOrNull(launch.index)?.let(::startMember)
        }
    }

    private fun finishGroup(runtime: JobRuntime, success: Boolean) {
        runtime.groupLaunch = null
        JobSounds.play(if (success) JobSounds.Kind.SUCCESS else JobSounds.Kind.ERROR)
    }

    // ---- Sounds -------------------------------------------------------------------------------------------

    private fun launchingGroupMembers(): Set<String> = JobBarService.get(project).jobs()
        .filter { it.isGroup && runtimes[it.id]?.groupLaunch != null }
        .flatMapTo(HashSet()) { group -> group.members.map { it.jobId } }

    /**
     * Plays a short sound for meaningful status changes of plain jobs. Runs on the EDT.
     * Members of a launching group stay quiet: the group plays one sound when its launch ends.
     */
    private fun playStatusSounds(launching: Set<String>) {
        JobBarService.get(project).jobs().filter { !it.isGroup }.forEach { job ->
            val runtime = runtimes[job.id] ?: return@forEach
            val previous = runtime.notifiedStatus
            val current = runtime.status
            if (previous == current) return@forEach
            runtime.notifiedStatus = current
            val quiet = job.id in launching
            when (current) {
                JobRuntimeStatus.SUCCESS -> if (!quiet && !runtime.inSequence) JobSounds.play(JobSounds.Kind.SUCCESS)
                // Tracked: the first ready after start, or a recovery from errors; plain rebuilds stay silent.
                JobRuntimeStatus.READY -> if (!runtime.readyNotified || previous == JobRuntimeStatus.WARNING) {
                    runtime.readyNotified = true
                    if (!quiet) JobSounds.play(JobSounds.Kind.SUCCESS)
                }
                JobRuntimeStatus.ERROR, JobRuntimeStatus.WARNING -> if (!quiet) JobSounds.play(JobSounds.Kind.ERROR)
                // A tracked process that ended by itself.
                JobRuntimeStatus.STOPPED -> if (!runtime.stoppedByUser && previous in activeStatuses) JobSounds.play(JobSounds.Kind.FINISHED)
                else -> Unit
            }
        }
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

    private fun fireChanged() = SwingUtilities.invokeLater {
        if (project.isDisposed) return@invokeLater
        // Taken before groups advance, so the member that completes a launch stays quiet too.
        val launchingMembers = launchingGroupMembers()
        refreshGroups()
        playStatusSounds(launchingMembers)
        listeners.toList().forEach { it() }
        // Main toolbar labels are action components; ask the platform to update them now.
        ActivityTracker.getInstance().inc()
    }

    override fun dispose() {
        runtimes.values.forEach {
            it.readyCheck?.cancel(false)
            it.stoppedByUser = true
            it.handler?.destroyProcess()
        }
        runtimes.clear()
    }

    companion object {
        fun get(project: Project): JobRuntimeManager = project.service()
    }
}

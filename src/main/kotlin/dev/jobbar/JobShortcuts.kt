package dev.jobbar

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Registers one "Run <job>" action per job so it can get a shortcut in Settings | Keymap (Plugins | Job Bar).
 * Action ids are built from the job id, so assigned shortcuts survive renames and IDE restarts.
 */
object JobShortcuts {
    private const val PREFIX = "JobBar.Run."
    private val pluginId = PluginId.getId("dev.jobbar")

    fun sync(project: Project) {
        val manager = ActionManager.getInstance()
        val jobs = JobBarService.get(project).jobs().filter { it.enabled }
        jobs.forEach { job ->
            val id = PREFIX + job.id
            val existing = manager.getAction(id)
            // A stale instance (e.g. left after a plugin reload) is replaced to avoid a duplicate-id error.
            if (existing != null && existing !is RunJobShortcutAction) manager.unregisterAction(id)
            val action = existing as? RunJobShortcutAction
                ?: RunJobShortcutAction(job.id).also { manager.registerAction(id, it, pluginId) }
            // Keymap lists the template text, so keep it in sync with the job name.
            action.templatePresentation.text = "Run ${job.name}"
            action.templatePresentation.description = "Job Bar: run ${job.name}"
        }
        // Drop actions of jobs deleted from this project since the last sync.
        val known = jobs.mapTo(HashSet()) { PREFIX + it.id }
        registered[project.locationHash]?.minus(known)?.forEach { manager.unregisterAction(it) }
        registered[project.locationHash] = known
    }

    // Action ids registered for each project at the last sync.
    private val registered = HashMap<String, Set<String>>()
}

class JobShortcutsStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater({
            if (!project.isDisposed) JobShortcuts.sync(project)
        }, project.disposed)
    }
}

private class RunJobShortcutAction(private val jobId: String) : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    private fun job(e: AnActionEvent): JobDefinition? =
        e.project?.let { project -> JobBarService.get(project).jobs().find { it.id == jobId && it.enabled } }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val job = job(e)
        e.presentation.isVisible = job != null
        e.presentation.isEnabled = job != null && project != null && !JobRuntimeManager.get(project).runtime(job).isActive()
        job?.let { e.presentation.text = "Run ${it.name}" }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val job = job(e) ?: return
        JobActions.get(project).runInteractive(job, e.dataContext)
    }
}

package dev.jobbar

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import java.util.concurrent.ConcurrentHashMap
import javax.swing.JComponent

object JobBarMainToolbarSettings {
    private const val KEY = "dev.jobbar.mainToolbar"

    fun isEnabled(project: Project): Boolean = PropertiesComponent.getInstance(project).getBoolean(KEY, true)
    fun setEnabled(project: Project, value: Boolean) = PropertiesComponent.getInstance(project).setValue(KEY, value, true)
}

/** Toggles the job labels in every toolbar that contains the Job Bar Jobs group. */
class ShowInMainToolbarAction : ToggleAction("Show Job Labels in Toolbars", "Show job labels in the main toolbar and other toolbars where Job Bar Jobs is added", null), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun isSelected(e: AnActionEvent): Boolean = e.project?.let(JobBarMainToolbarSettings::isEnabled) ?: false
    override fun setSelected(e: AnActionEvent, state: Boolean) {
        e.project?.let { JobBarMainToolbarSettings.setEnabled(it, state) }
    }
}

/** Registered in MainToolbarRight; yields one label action per job, in the job list order. */
class JobBarMainToolbarGroup : ActionGroup(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val project = e?.project ?: return EMPTY_ARRAY
        if (!JobBarMainToolbarSettings.isEnabled(project)) return EMPTY_ARRAY
        // Cached per job so the toolbar keeps the same components between updates.
        val chips = JobBarService.get(project).jobs()
            .filter { it.enabled }
            .map { job -> chipActions.getOrPut(job.id) { JobChipAction(job.id) } }
        return (chips + toggleToolWindow).toTypedArray()
    }

    private companion object {
        val chipActions = ConcurrentHashMap<String, JobChipAction>()
        val toggleToolWindow = ToggleJobBarToolWindowAction()
    }
}

private class JobChipAction(private val jobId: String) : AnAction(), CustomComponentAction, DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val job = project?.let { JobBarService.get(it).jobs().find { job -> job.id == jobId && job.enabled } }
        e.presentation.isEnabledAndVisible = job != null
        e.presentation.putClientProperty(PROJECT, project)
        e.presentation.putClientProperty(JOB, job)
        e.presentation.text = job?.compactLabel
    }

    // Clicks are handled by the chip component itself.
    override fun actionPerformed(e: AnActionEvent) = Unit

    override fun createCustomComponent(presentation: Presentation, place: String): JComponent = JobChip()

    override fun updateCustomComponent(component: JComponent, presentation: Presentation) {
        val project = presentation.getClientProperty(PROJECT) ?: return
        val job = presentation.getClientProperty(JOB) ?: return
        (component as JobChip).bind(project, job, JobRuntimeManager.get(project).runtime(job))
    }

    private companion object {
        val PROJECT = Key.create<Project>("JobBar.project")
        val JOB = Key.create<JobDefinition>("JobBar.job")
    }
}

/** Shows the Job Bar tool window, or hides it when it is already visible. */
class ToggleJobBarToolWindowAction : DumbAwareAction("Job Bar", "Show or hide the Job Bar tool window", AllIcons.Actions.ListFiles) {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun actionPerformed(e: AnActionEvent) {
        val toolWindow = e.project?.let { ToolWindowManager.getInstance(it).getToolWindow("Job Bar") } ?: return
        if (toolWindow.isVisible) toolWindow.hide() else toolWindow.activate(null)
    }
}

package dev.jobbar

import com.intellij.icons.AllIcons
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import javax.swing.JComponent

/** Per-job actions shared by the tool window rows, the compact view and the main toolbar. */
@Service(Service.Level.PROJECT)
class JobActions(private val project: Project) {
    private val service get() = JobBarService.get(project)
    private val runtimes get() = JobRuntimeManager.get(project)

    fun runAction(job: JobDefinition, text: String = "Run"): AnAction = RunJobAction(job, text)
    fun stopAction(job: JobDefinition): AnAction = StopJobAction(job)
    fun restartAction(job: JobDefinition): AnAction = RestartJobAction(job)
    fun openConsoleAction(job: JobDefinition): AnAction = OpenConsoleAction(job)
    fun editAction(job: JobDefinition): AnAction = EditJobAction(job)

    /** Runs the job right away, or asks for a variant first when it has any. */
    fun runInteractive(job: JobDefinition, dataContext: DataContext) {
        if (!job.enabled) return
        if (job.isGroup || job.variants.isEmpty()) {
            runtimes.run(job)
            return
        }
        JBPopupFactory.getInstance().createActionGroupPopup(
            "Run ${job.name}",
            DefaultActionGroup(variantActions(job)),
            dataContext,
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
            true
        ).showInBestPositionFor(dataContext)
    }

    fun showJobMenu(job: JobDefinition, anchor: JComponent) {
        JBPopupFactory.getInstance().createActionGroupPopup(
            job.name,
            contextActions(job, variantsAsSubmenu = true),
            DataManager.getInstance().getDataContext(anchor),
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
            true
        ).showUnderneathOf(anchor)
    }

    /** Variant run actions: "All" first (when enabled), then each variant. */
    private fun variantActions(job: JobDefinition): List<AnAction> = buildList {
        if (job.runAllVariants && job.runMode == JobRunMode.ONE_SHOT && job.variants.size > 1) {
            add(object : DumbAwareAction("All", "Run all ${job.variants.size} variants one after another", AllIcons.Actions.RunAll) {
                override fun actionPerformed(e: AnActionEvent) = runtimes.runAll(job)
            })
            add(Separator.getInstance())
        }
        job.variants.forEach { variant ->
            add(object : DumbAwareAction(variant.label, variant.value, AllIcons.Actions.Execute) {
                override fun actionPerformed(e: AnActionEvent) = runtimes.run(job, variant)
            })
        }
    }

    fun contextActions(job: JobDefinition, variantsAsSubmenu: Boolean = false): DefaultActionGroup = DefaultActionGroup().apply {
        val runtime = runtimes.runtime(job)
        if (variantsAsSubmenu && job.variants.isNotEmpty() && !job.isGroup) {
            // A nested popup cannot be opened from inside a popup, so variants become a submenu.
            if (!runtime.isActive() && job.enabled) add(DefaultActionGroup("Run", variantActions(job)).apply {
                isPopup = true
                templatePresentation.icon = AllIcons.Actions.Execute
            })
        } else {
            add(RunJobAction(job, if (job.variants.isEmpty() || job.isGroup) "Run" else "Run…"))
        }
        if (runtime.isActive()) add(StopJobAction(job))
        if (!runtime.inSequence && (runtime.status in activeStatuses || job.isGroup && runtime.isActive())) add(RestartJobAction(job))
        add(OpenConsoleAction(job))
        addSeparator()
        val index = service.jobs().indexOfFirst { it.id == job.id }
        add(object : DumbAwareAction("Move Up", "Move job up", AllIcons.Actions.MoveUp) {
            override fun update(e: AnActionEvent) { e.presentation.isEnabled = index > 0 }
            override fun actionPerformed(e: AnActionEvent) = service.moveBy(job.id, -1)
        })
        add(object : DumbAwareAction("Move Down", "Move job down", AllIcons.Actions.MoveDown) {
            override fun update(e: AnActionEvent) { e.presentation.isEnabled = index >= 0 && index < service.jobs().lastIndex }
            override fun actionPerformed(e: AnActionEvent) = service.moveBy(job.id, 1)
        })
        addSeparator()
        add(EditJobAction(job))
        add(object : ToggleAction("Enabled", "Disabled jobs are hidden from toolbars and shortcuts and cannot run", null), DumbAware {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun isSelected(e: AnActionEvent): Boolean = job.enabled
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                if (!state) runtimes.stop(job)
                service.save(job.copyDeep().apply { enabled = state })
            }
        })
        add(object : DumbAwareAction("Copy Job", "Create a copy of this job", AllIcons.Actions.Copy) {
            override fun actionPerformed(e: AnActionEvent) = service.duplicate(job)
        })
        add(object : DumbAwareAction("Delete Job", null, AllIcons.General.Remove) {
            override fun actionPerformed(e: AnActionEvent) {
                if (Messages.showYesNoDialog(project, "Delete '${job.name}'?", "Delete Job", AllIcons.General.QuestionDialog) == Messages.YES) {
                    runtimes.stop(job)
                    service.delete(job)
                }
            }
        })
    }

    private inner class EditJobAction(private val job: JobDefinition) :
        DumbAwareAction("Edit Job…", "Edit job", AllIcons.Actions.Edit) {
        override fun actionPerformed(e: AnActionEvent) {
            if (job.isGroup) {
                val dialog = GroupEditorDialog(project, job)
                if (dialog.showAndGet()) service.save(dialog.result())
            } else {
                val dialog = JobEditorDialog(project, job)
                if (dialog.showAndGet()) service.save(dialog.result())
            }
        }
    }

    private inner class RunJobAction(
        private val job: JobDefinition,
        text: String
    ) : DumbAwareAction(text, "Run ${job.name}", AllIcons.Actions.Execute) {
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = job.enabled && !runtimes.runtime(job).isActive()
        }

        override fun actionPerformed(e: AnActionEvent) = runInteractive(job, e.dataContext)
    }

    private inner class StopJobAction(private val job: JobDefinition) :
        DumbAwareAction("Stop", "Stop ${job.name}", AllIcons.Actions.Suspend) {
        override fun actionPerformed(e: AnActionEvent) = runtimes.stop(job)
    }

    private inner class RestartJobAction(private val job: JobDefinition) :
        DumbAwareAction("Restart", "Restart ${job.name}", AllIcons.Actions.Restart) {
        override fun actionPerformed(e: AnActionEvent) = runtimes.restart(job)
    }

    private inner class OpenConsoleAction(private val job: JobDefinition) :
        DumbAwareAction("Open Console", "Open command output", AllIcons.Debugger.Console) {
        override fun actionPerformed(e: AnActionEvent) = runtimes.openConsole(job)
    }

    companion object {
        fun get(project: Project): JobActions = project.service()
    }
}

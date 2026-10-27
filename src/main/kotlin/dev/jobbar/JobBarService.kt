package dev.jobbar

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer

@Service(Service.Level.PROJECT)
@State(name = "JobBar", storages = [Storage("jobBar.xml")])
class JobBarService(private val project: Project) : PersistentStateComponent<JobBarService.State> {
    data class State(var jobs: MutableList<JobDefinition> = mutableListOf())

    private var state = State()
    private val listeners = mutableListOf<() -> Unit>()

    override fun getState(): State = state
    override fun loadState(state: State) { this.state = state }

    fun jobs(): List<JobDefinition> = state.jobs

    fun save(job: JobDefinition) {
        val index = state.jobs.indexOfFirst { it.id == job.id }
        if (index >= 0) state.jobs[index] = job.copyDeep() else state.jobs.add(job.copyDeep())
        changed()
    }

    fun delete(job: JobDefinition) {
        state.jobs.removeIf { it.id == job.id }
        changed()
    }

    fun duplicate(job: JobDefinition) {
        val copy = job.copyDeep().apply {
            id = java.util.UUID.randomUUID().toString()
            name = "$name Copy"
        }
        state.jobs.add(copy)
        changed()
    }

    fun moveToIndex(jobId: String, targetIndex: Int) {
        val fromIndex = state.jobs.indexOfFirst { it.id == jobId }
        if (fromIndex < 0) return
        val job = state.jobs.removeAt(fromIndex)
        val destination = targetIndex.coerceIn(0, state.jobs.size)
        if (fromIndex == destination || (fromIndex == state.jobs.size && destination == state.jobs.size)) {
            state.jobs.add(fromIndex.coerceAtMost(state.jobs.size), job)
            return
        }
        state.jobs.add(destination, job)
        changed()
    }

    fun moveBy(jobId: String, delta: Int) {
        val index = state.jobs.indexOfFirst { it.id == jobId }
        if (index < 0) return
        moveToIndex(jobId, index + delta)
    }

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun exportXml(): String = JDOMUtil.writeElement(XmlSerializer.serialize(state))

    fun importXml(xml: String) {
        val imported = XmlSerializer.deserialize(JDOMUtil.load(xml), State::class.java)
        state = State(imported.jobs.map { it.copyDeep() }.toMutableList())
        changed()
    }

    private fun changed() {
        listeners.toList().forEach { it() }
    }

    companion object {
        fun get(project: Project): JobBarService = project.service()
    }
}

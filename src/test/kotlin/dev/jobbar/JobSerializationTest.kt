package dev.jobbar

import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JobSerializationTest {
    private fun load(xml: String): JobBarService.State =
        XmlSerializer.deserialize(JDOMUtil.load(xml), JobBarService.State::class.java)

    private fun save(state: JobBarService.State): String = JDOMUtil.writeElement(XmlSerializer.serialize(state))

    @Test
    fun `enums are written by name`() {
        val state = JobBarService.State(mutableListOf(
            JobDefinition(name = "Dev", runMode = JobRunMode.GROUP, members = mutableListOf(GroupMember("a"))),
            JobDefinition(name = "Front", runMode = JobRunMode.TRACKED, type = JobType.ANGULAR, variantSortOrder = VariantSortOrder.VALUE_DESC)
        ))
        val xml = save(state)
        assertTrue(xml, xml.contains("value=\"GROUP\""))
        assertTrue(xml, xml.contains("value=\"TRACKED\""))
        assertTrue(xml, xml.contains("value=\"ANGULAR\""))
        assertTrue(xml, xml.contains("value=\"VALUE_DESC\""))
        val reloaded = load(xml).jobs
        assertEquals(JobRunMode.GROUP, reloaded[0].runMode)
        assertEquals(JobRunMode.TRACKED, reloaded[1].runMode)
        assertEquals(JobType.ANGULAR, reloaded[1].type)
        assertEquals(VariantSortOrder.VALUE_DESC, reloaded[1].variantSortOrder)
    }

    @Test
    fun `old label values are still read`() {
        val xml = """
            <State><option name="jobs"><list>
              <JobDefinition>
                <option name="name" value="Front" />
                <option name="runMode" value="Tracked process" />
                <option name="type" value="npm / Node.js" />
                <option name="variantSortOrder" value="Label Z → A" />
              </JobDefinition>
              <JobDefinition>
                <option name="name" value="Dev" />
                <option name="runMode" value="Group" />
              </JobDefinition>
              <JobDefinition>
                <option name="name" value="Odd" />
                <option name="runMode" value="Something unknown" />
              </JobDefinition>
            </list></option></State>
        """.trimIndent()
        val jobs = load(xml).jobs
        assertEquals(JobRunMode.TRACKED, jobs[0].runMode)
        assertEquals(JobType.NODE, jobs[0].type)
        assertEquals(VariantSortOrder.LABEL_DESC, jobs[0].variantSortOrder)
        assertEquals(JobRunMode.GROUP, jobs[1].runMode)
        // Unknown values fall back to the default instead of breaking the load.
        assertEquals(JobRunMode.ONE_SHOT, jobs[2].runMode)
    }

    @Test
    fun `exported file from an older version loads`() {
        val xml = """
            <State><option name="jobs"><list>
              <JobDefinition>
                <option name="command" value="yarn lokaliser fetch affiliate_top ${'$'}{file_name}" />
                <option name="name" value="Translates" />
                <option name="parameterName" value="file_name" />
                <option name="runMode" value="One-shot command" />
                <option name="type" value="Generic command" />
                <option name="variants"><list><JobVariant><option name="label" value="common" /><option name="value" value="common" /></JobVariant></list></option>
              </JobDefinition>
            </list></option></State>
        """.trimIndent()
        val job = load(xml).jobs.single()
        assertEquals(JobRunMode.ONE_SHOT, job.runMode)
        assertEquals(JobType.GENERIC, job.type)
        assertEquals("common", job.variants.single().value)
    }
}

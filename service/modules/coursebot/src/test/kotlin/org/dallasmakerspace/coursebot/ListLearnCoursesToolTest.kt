package org.dallasmakerspace.coursebot

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.dallasmakerspace.agent.ToolContext
import org.dallasmakerspace.coursebot.tools.ListLearnCoursesTool
import org.dallasmakerspace.moodle.LearnCatalog
import org.dallasmakerspace.moodle.LearnCourse
import org.dallasmakerspace.moodle.MoodleCatalogService
import org.junit.Test
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

class ListLearnCoursesToolTest {
  private fun course(id: Int, name: String, category: String, committee: String? = null) =
      LearnCourse(
          id, "c$id", name, "About $name", "https://learn.example.org/course/view.php?id=$id", true,
          category, committeeName = committee)

  private val catalog =
      mock<MoodleCatalogService> {
        onBlocking { getCatalog(anyOrNull()) } doReturn
            LearnCatalog(
                "https://learn.example.org",
                listOf(
                    course(1, "Laser Safety", "Laser", "Laser"),
                    course(2, "Welding Basics", "Metal"),
                    course(3, "Sewing 101", "Textiles")))
      }
  private val context = ToolContext("jdoe", "c1", "course-assistant")

  @Test
  fun `it lists published courses with links and filters by name, category or committee`() = runBlocking<Unit> {
    val tool = ListLearnCoursesTool(catalog)

    val all = tool.run(JsonObject(emptyMap()), context)
    assertEquals(3, all.sources.size)
    assertTrue(all.output.contains("Link: https://learn.example.org/course/view.php?id=2"))

    val metal = tool.run(buildJsonObject { put("filter", "METAL") }, context)
    assertEquals(listOf("Welding Basics"), metal.sources.map { it.title })
    assertEquals(listOf("Laser Safety"), tool.run(buildJsonObject { put("filter", "laser") }, context).sources.map { it.title })

    val none = tool.run(buildJsonObject { put("filter", "pottery") }, context)
    assertFalse(none.isError)
    assertEquals("No published courses matched.", none.output)
  }
}

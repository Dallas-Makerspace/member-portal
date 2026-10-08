package org.dallasmakerspace.server.moodle

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.models.Committees
import org.dallasmakerspace.server.memberservice.MemberServiceClient
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class MoodleCoursesCommitteeSectionTest {

  private val client = mock<MemberServiceClient>()
  private val loader = MoodleCommitteeCoursesLoader(client)
  private val laser = Committees.findBySlug("laser")!!

  private fun catalog(count: Int) =
      mapOf(
          "learnUrl" to "https://learn.example.org",
          "courses" to
              (1..count).map {
                mapOf(
                    "id" to it.toDouble(),
                    "name" to "Example $it",
                    "url" to "https://learn.example.org/course/view.php?id=$it",
                    "committeeSlug" to "laser",
                )
              },
      )

  @Test
  fun `section is a placeholder that makes no backend calls`() = runBlocking<Unit> {
    val section = MoodleCoursesCommitteeSection()

    val rendered = assertNotNull(section.renderSection(laser, "sid", "jdoe"))

    assertEquals("fragments/moodle-courses-placeholder", rendered.fragmentName)
    assertEquals("laser", rendered.model["committeeSlug"])
    assertNull(section.renderSection(laser, "sid", null))
  }

  @Test
  fun `loader returns nothing when the flag is off`() = runBlocking<Unit> {
    whenever(client.isFeatureEnabled(eq(MOODLE_FEATURE_FLAG), eq("jdoe"), anyOrNull())).thenReturn(false)

    assertNull(loader.load(laser, "sid", "jdoe"))
    verify(client, never()).getMoodleCatalog(anyOrNull(), anyOrNull())
  }

  @Test
  fun `loader returns nothing for committees without courses`() = runBlocking<Unit> {
    whenever(client.isFeatureEnabled(any(), any(), anyOrNull())).thenReturn(true)
    whenever(client.getMoodleCatalog(anyOrNull(), eq("laser"))).thenReturn(catalog(0))
    whenever(client.getMoodleMemberCourses(anyOrNull(), eq("jdoe"))).thenReturn(emptyList())

    assertNull(loader.load(laser, "sid", "jdoe"))
  }

  @Test
  fun `loader shows the first three courses and collapses the rest`() = runBlocking<Unit> {
    whenever(client.isFeatureEnabled(any(), any(), anyOrNull())).thenReturn(true)
    whenever(client.getMoodleCatalog(anyOrNull(), eq("laser"))).thenReturn(catalog(5))
    whenever(client.getMoodleMemberCourses(anyOrNull(), eq("jdoe"))).thenReturn(emptyList())

    val model = assertNotNull(loader.load(laser, "sid", "jdoe"))

    assertEquals(3, (model["courses"] as List<*>).size)
    assertEquals(2, (model["moreCourses"] as List<*>).size)
    assertEquals("/reports/online-courses/overview?committee=laser", model["statsUrl"])
  }
}

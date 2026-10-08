package org.dallasmakerspace.moodle

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.models.Committees
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.slf4j.Logger

class MoodleCatalogServiceTest {

  private val learnUrl = "https://learn.example.org"
  private val woodshop = Committees.findBySlug("woodshop")!!

  private val categories =
      listOf(
          // Matched by idnumber, using whatever the committee is configured with.
          MoodleCategory(
              id = 1,
              name = "Example Wood Category",
              idnumber = woodshop.learnCategories.first(),
              path = "/1"),
          MoodleCategory(id = 2, name = "Example CNC", parent = 1, path = "/1/2"),
          // Matched by name.
          MoodleCategory(id = 3, name = "Laser", path = "/3"),
          MoodleCategory(id = 4, name = "Example Unmapped", path = "/4"),
          MoodleCategory(id = 5, name = "Example Hidden", path = "/5", visible = 0),
      )

  private fun course(id: Int, categoryId: Int, sortorder: Int, visible: Int = 1) =
      MoodleCourse(
          id = id,
          shortname = "EX-$id",
          fullname = "Example Course $id",
          categoryid = categoryId,
          sortorder = sortorder,
          summary = "<p>Learn &amp; practice</p>",
          visible = visible,
      )

  @Test
  fun `maps courses in subcategories to the top-level category's committee`() {
    val courses = MoodleCatalogService.buildCourses(categories, listOf(course(10, 2, 1)), learnUrl)

    val c = courses.single()
    assertEquals("woodshop", c.committeeSlug)
    assertEquals("Example Wood Category", c.categoryName)
    assertEquals("$learnUrl/course/view.php?id=10", c.url)
    assertEquals("Learn & practice", c.summary)
  }

  @Test
  fun `falls back to matching the category name against the committee name`() {
    val c = MoodleCatalogService.buildCourses(categories, listOf(course(11, 3, 1)), learnUrl).single()
    assertEquals("laser", c.committeeSlug)
  }

  @Test
  fun `first course contact becomes the author, with a username when resolvable`() {
    val withAuthor =
        course(13, 3, 1).copy(contacts = listOf(MoodleCourseContact(77, "Jane &amp; Doe")))
    val unresolved = course(14, 3, 2).copy(contacts = listOf(MoodleCourseContact(78, "Alex Smith")))

    val courses =
        MoodleCatalogService.buildCourses(
            categories, listOf(withAuthor, unresolved, course(15, 3, 3)), learnUrl, mapOf(77 to "jdoe"))

    assertEquals("Jane & Doe", courses[0].authorName)
    assertEquals("jdoe", courses[0].authorUsername)
    assertEquals("Alex Smith", courses[1].authorName)
    assertNull(courses[1].authorUsername)
    assertNull(courses[2].authorName)
  }

  @Test
  fun `self enrolment comes from the course's enabled enrolment methods`() {
    val courses =
        MoodleCatalogService.buildCourses(
            categories,
            listOf(
                course(16, 3, 1).copy(enrollmentmethods = listOf("manual", "self")),
                course(17, 3, 2).copy(enrollmentmethods = listOf("manual"))),
            learnUrl)

    assertEquals(listOf(true, false), courses.map { it.selfEnrol })
  }

  @Test
  fun `unmapped categories leave the committee empty`() {
    val c = MoodleCatalogService.buildCourses(categories, listOf(course(12, 4, 1)), learnUrl).single()
    assertNull(c.committeeSlug)
  }

  @Test
  fun `hidden courses and courses in hidden categories are not visible, and sortorder is kept`() {
    val courses =
        MoodleCatalogService.buildCourses(
            categories,
            listOf(course(3, 3, 30), course(1, 1, 10, visible = 0), course(2, 5, 20)),
            learnUrl,
        )
    assertEquals(listOf(1, 2, 3), courses.map { it.id })
    assertFalse(courses[0].visible)
    assertFalse(courses[1].visible)
    assertTrue(courses[2].visible)
  }

  @Test
  fun `getCatalog filters to visible courses of one committee`() = runBlocking<Unit> {
    val client = mock<IMoodleApiClient>()
    whenever(client.baseUrl).thenReturn(learnUrl)
    whenever(client.getCategories()).thenReturn(categories)
    whenever(client.getCourses())
        .thenReturn(listOf(course(1, 1, 1), course(2, 1, 2, visible = 0), course(3, 3, 3)))
    whenever(client.getUsernames(any())).thenReturn(emptyMap())
    val loggerFactory = mock<LoggerFactory>()
    whenever(loggerFactory.create(any())).thenReturn(mock<Logger>())
    val service = MoodleCatalogService(client, loggerFactory)

    val catalog = service.getCatalog(woodshop)

    assertEquals(learnUrl, catalog.learnUrl)
    assertEquals(listOf(1), catalog.courses.map { it.id })
    assertEquals(3, service.getAllCourses().size)
  }
}

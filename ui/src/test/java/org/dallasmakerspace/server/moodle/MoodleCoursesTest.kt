package org.dallasmakerspace.server.moodle

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class MoodleCoursesTest {

  // Shaped like the service response after Gson (numbers come back as Double).
  private fun course(id: Int, committeeSlug: String?) =
      mapOf(
          "id" to id.toDouble(),
          "name" to "Example Course $id",
          "summary" to "Summary $id",
          "url" to "https://learn.example.org/course/view.php?id=$id",
          "categoryName" to "Example",
          "committeeSlug" to committeeSlug,
          "committeeName" to committeeSlug,
      )

  private val catalog =
      mapOf(
          "learnUrl" to "https://learn.example.org",
          "courses" to
              listOf(
                  course(1, "laser"),
                  course(2, "woodshop"),
                  course(3, "laser"),
                  course(4, null),
                  course(5, "woodshop"),
              ),
      )

  private val memberCourses =
      listOf(
          mapOf("courseId" to 1.0, "progress" to 100.0, "completed" to true),
          mapOf("courseId" to 4.0, "progress" to 40.0, "completed" to false),
      )

  @Test
  fun `parse merges the member's status into catalog courses`() {
    val courses = MoodleCourses.parse(catalog, memberCourses).associateBy { it.id }

    assertTrue(courses.getValue(1).completed)
    assertFalse(courses.getValue(1).inProgress)
    assertTrue(courses.getValue(4).inProgress)
    assertEquals(40, courses.getValue(4).progress)
    assertFalse(courses.getValue(2).enrolled)
    assertEquals("https://learn.example.org", MoodleCourses.learnUrl(catalog))
  }

  @Test
  fun `parse keeps the course author for display`() {
    val withAuthor =
        catalog + ("courses" to listOf(course(9, "laser") + mapOf("authorName" to "Jane Doe", "authorUsername" to "jdoe")))

    val course = MoodleCourses.parse(withAuthor).single()

    assertEquals("Jane Doe", course.authorName)
    assertEquals("jdoe", course.authorUsername)
    assertEquals("Jane Doe", course.toJson()["authorName"])
  }

  @Test
  fun `parse tolerates a missing catalog`() {
    assertTrue(MoodleCourses.parse(null).isEmpty())
  }

  @Test
  fun `suggest puts in-progress first, then the member's committees, and skips completed`() {
    val courses = MoodleCourses.parse(catalog, memberCourses)

    val picks = MoodleCourses.suggest(courses, setOf("/Woodshop Example Group"), limit = 4)

    // One per committee first (in-progress, woodshop, laser), then the second woodshop course.
    assertEquals(listOf(4, 2, 3, 5), picks.map { it.id })
  }

  @Test
  fun `suggest spreads picks across committees when there are enough`() {
    val many =
        catalog +
            ("courses" to
                listOf(
                    course(1, "woodshop"),
                    course(2, "woodshop"),
                    course(3, "woodshop"),
                    course(4, "woodshop"),
                    course(5, "laser"),
                    course(6, "3d-fab"),
                ))

    val picks = MoodleCourses.suggest(MoodleCourses.parse(many), setOf("Woodshop Example Group"), limit = 4)

    assertEquals(listOf(1, 5, 6, 2), picks.map { it.id })
  }

  @Test
  fun `suggest falls back to catalog order for members with no matching groups`() {
    val picks = MoodleCourses.suggest(MoodleCourses.parse(catalog), emptySet(), limit = 2)
    assertEquals(listOf(1, 2), picks.map { it.id })
  }
}

package org.dallasmakerspace.notifications.templates

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.dallasmakerspace.notifications.topics.CommitteeUpdatesTopic
import org.junit.Test

class CommitteeCourseTemplateTest {

  private val template = CommitteeCourseTemplate()

  private fun data() =
      CommitteeCourseData(
          courseName = "Laser Safety & Orientation",
          committeeName = "Laser",
          courseUrl = "https://learn.example.org/course/view.php?id=42")

  @Test
  fun `uses the committee updates topic, the same toggle as classes`() {
    assertEquals(CommitteeUpdatesTopic, template.topic)
    assertEquals("committee-course", template.name)
  }

  @Test
  fun `subject names the committee and the course`() {
    assertEquals(
        "New online course from Laser: Laser Safety & Orientation", template.renderSubject(data()))
  }

  @Test
  fun `text and html say why the member is getting it and link the course`() {
    val text = template.renderText(data())
    val html = template.renderHtml(data())

    assertTrue(text.contains("You are receiving this because you follow Laser."))
    assertTrue(text.contains("https://learn.example.org/course/view.php?id=42"))
    assertTrue(html.contains("<a href=\"https://learn.example.org/course/view.php?id=42\">"))
    assertTrue(!text.contains("<a href"))
  }

  @Test
  fun `html escapes the committee and course names`() {
    val html = template.renderHtml(data().copy(committeeName = "<b>Laser</b>"))

    assertTrue(!html.contains("<b>Laser</b>"))
    assertTrue(html.contains("Laser Safety &amp; Orientation"))
  }

  @Test
  fun `wording does not promise a delivery channel`() {
    val all = template.renderSubject(data()) + template.renderText(data()) + template.renderHtml(data())

    assertTrue(!all.contains("email", ignoreCase = true))
  }
}

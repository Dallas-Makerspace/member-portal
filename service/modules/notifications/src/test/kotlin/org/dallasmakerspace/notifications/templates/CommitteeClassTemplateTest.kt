package org.dallasmakerspace.notifications.templates

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.dallasmakerspace.notifications.topics.CommitteeUpdatesTopic
import org.junit.Test

class CommitteeClassTemplateTest {

  private val template = CommitteeClassTemplate()

  private fun data() =
      CommitteeClassData(
          eventName = "Intro to Laser Cutting",
          committeeName = "Laser",
          eventStart = "Mon Aug 10 7:00 PM",
          eventUrl = "https://calendar.dallasmakerspace.org/events/view/12345")

  @Test
  fun `uses the committee updates topic`() {
    assertEquals(CommitteeUpdatesTopic, template.topic)
  }

  @Test
  fun `subject names the committee and includes the start time`() {
    assertEquals(
        "New class from Laser: Intro to Laser Cutting (Mon Aug 10 7:00 PM)",
        template.renderSubject(data()))
    assertEquals(
        "New class from Laser: Intro to Laser Cutting",
        template.renderSubject(data().copy(eventStart = "")))
  }

  @Test
  fun `text and html say why the member is getting it and link the event`() {
    val text = template.renderText(data())
    val html = template.renderHtml(data())

    assertTrue(text.contains("You are receiving this because you follow Laser."))
    assertTrue(text.contains("https://calendar.dallasmakerspace.org/events/view/12345"))
    assertTrue(html.contains("<a href=\"https://calendar.dallasmakerspace.org/events/view/12345\">"))
    assertTrue(!text.contains("<a href"))
  }

  @Test
  fun `html escapes the committee and event names`() {
    val html =
        template.renderHtml(data().copy(committeeName = "<b>Laser</b>", eventName = "A & B"))

    assertTrue(!html.contains("<b>Laser</b>"))
    assertTrue(html.contains("A &amp; B"))
  }

  @Test
  fun `combined subject counts the updates`() {
    assertEquals("3 new updates from committees you follow", CommitteeUpdatesTopic.combinedSubject(3))
  }
}

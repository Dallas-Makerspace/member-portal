package org.dallasmakerspace.notifications.templates

import kotlin.test.assertTrue
import org.junit.Test

class EventAnnouncementTemplateTest {

  private val template = EventAnnouncementTemplate()

  private fun data() =
      EventAnnouncementData(
          eventName = "Intro to Laser Cutting",
          organizerName = "Jane Doe",
          organizerUsername = "jdoe",
          eventStart = "Mon Aug 10 7:00 PM",
          eventUrl = "https://calendar.dallasmakerspace.org/events/view/12345")

  @Test
  fun `subject should include the organizer's full name`() {
    assertTrue(template.renderSubject(data()).contains("Jane Doe"))
  }

  @Test
  fun `html should render Full Name (@username) with the username linked to their profile`() {
    val html = template.renderHtml(data())

    assertTrue(html.contains("Jane Doe ("))
    assertTrue(
        html.contains(
            "<a href=\"https://members.dallasmakerspace.org/profile/@jdoe\">@jdoe</a>"))
  }

  @Test
  fun `text should render Full Name (@username) without markup`() {
    val text = template.renderText(data())

    assertTrue(text.contains("Jane Doe (@jdoe)"))
    assertTrue(!text.contains("<a href"))
  }

  @Test
  fun `html should escape organizer name and event name`() {
    val html =
        template.renderHtml(
            data().copy(organizerName = "<script>alert(1)</script>", eventName = "A & B"))

    assertTrue(!html.contains("<script>"))
    assertTrue(html.contains("A &amp; B"))
  }
}

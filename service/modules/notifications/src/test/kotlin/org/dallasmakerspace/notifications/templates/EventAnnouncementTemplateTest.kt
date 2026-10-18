package org.dallasmakerspace.notifications.templates

import kotlin.test.assertEquals
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
  fun `subject should include the start time so repeat sessions are distinguishable`() {
    assertEquals(
        "New event from Jane Doe: Intro to Laser Cutting (Mon Aug 10 7:00 PM)",
        template.renderSubject(data()))
    assertEquals(
        "New event from Jane Doe: Intro to Laser Cutting",
        template.renderSubject(data().copy(eventStart = "")))
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

  @Test
  fun `html shows the event image linked to the event`() {
    val html = template.renderHtml(data().copy(imageUrl = "https://calendar.example.org/files/1/a b.png"))

    assertTrue(
        Regex("<a href=\"https://calendar.dallasmakerspace.org/events/view/12345\"[^>]*><img class=\"dms-thumb\" src=\"https://calendar.example.org/files/1/a b.png\"")
            .containsMatchIn(html),
        html)
    assertTrue(html.contains("alt=\"Intro to Laser Cutting\""))
    assertTrue(html.indexOf("dms-thumb") < html.indexOf("Intro to Laser Cutting</h3>"))
    assertTrue(html.indexOf("You are receiving this") > html.indexOf("View the full details"))
  }

  @Test
  fun `html has no image markup when the event has none`() {
    val html = template.renderHtml(data())

    assertTrue(!html.contains("<img") && !html.contains("dms-thumb"), html)
    assertTrue(html.contains("View the full details"))
  }

  @Test
  fun `the image does not change the subject or the text`() {
    val withImage = data().copy(imageUrl = "https://calendar.example.org/files/1/a.png")

    assertEquals(template.renderSubject(data()), template.renderSubject(withImage))
    assertEquals(template.renderText(data()), template.renderText(withImage))
  }

  @Test
  fun `html escapes the image url`() {
    val html = template.renderHtml(data().copy(imageUrl = "https://x.example.org/a\"onerror=\"x.png"))

    assertTrue(!html.contains("src=\"https://x.example.org/a\"onerror"), html)
  }

  @Test
  fun `html shows the description teaser, escaped, with or without an image`() {
    val withText = data().copy(excerpt = "Bring <b>glasses</b> & gloves")

    for (html in listOf(template.renderHtml(withText), template.renderHtml(withText.copy(imageUrl = "https://calendar.example.org/files/1/a.png")))) {
      assertTrue(html.contains("Bring &lt;b&gt;glasses&lt;/b&gt; &amp; gloves"), html)
      assertTrue(html.indexOf("Bring &lt;") < html.indexOf("View the full details"))
    }
    assertTrue(template.renderText(withText).contains("Bring <b>glasses</b> & gloves"))
    assertTrue(!template.renderHtml(data()).contains("color:#555"))
  }
}

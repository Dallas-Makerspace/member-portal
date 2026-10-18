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

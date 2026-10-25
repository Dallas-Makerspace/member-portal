package org.dallasmakerspace.notifications.templates

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test

class CommitteeMessageTemplateTest {

  private val template = CommitteeMessageTemplate()
  private val data =
      CommitteeMessageData(
          committeeName = "Laser",
          message = "Meeting moved to Thursday at 7",
          shortUrl = "https://short.example.test/abc123")

  @Test
  fun `subject carries the committee and the whole message`() {
    assertEquals("Laser: Meeting moved to Thursday at 7", template.renderSubject(data))
  }

  @Test
  fun `text body has the message, the link and why it was sent`() {
    val text = template.renderText(data)

    assertTrue("Meeting moved to Thursday at 7" in text)
    assertTrue("https://short.example.test/abc123" in text)
    assertTrue("because you follow Laser" in text)
  }

  @Test
  fun `html escapes the message and committee name`() {
    val html =
        template.renderHtml(
            data.copy(committeeName = "<b>Laser</b>", message = "<script>alert(1)</script> & more"))

    assertFalse("<script>" in html)
    assertFalse("<b>Laser</b>" in html)
    assertTrue("&lt;script&gt;alert(1)&lt;/script&gt; &amp; more" in html)
    assertTrue("href=\"https://short.example.test/abc123\"" in html)
  }

  @Test
  fun `inbox card opens the thread link`() {
    val card = assertNotNull(template.card(data))

    assertEquals(CardKinds.MESSAGE, card.kind)
    assertEquals("Meeting moved to Thursday at 7", card.title)
    assertEquals("https://short.example.test/abc123", card.url)
    assertEquals("Join the discussion", card.ctaLabel)
    assertEquals("Laser", card.sender)
  }
}

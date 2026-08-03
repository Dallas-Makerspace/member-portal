package org.dallasmakerspace.notifications

import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class DigestBodyBuilderTest {

  private val utcZone = ZoneId.of("UTC")

  private fun notification(id: Long, subject: String, body: String?) =
      Notification(
          id = id,
          memberUsername = "jdoe",
          topic = NotificationTopic.SYSTEM,
          channel = Channel.EMAIL,
          aggregation = Aggregation.DAILY,
          templateName = "system-test",
          payload = null,
          subject = subject,
          body = body,
          status = NotificationStatus.IN_DIGEST,
          attempts = 0,
          nextAttemptAt = null,
          dedupeKey = null,
          providerId = null,
          lastError = null,
          sentAt = null,
          readAt = null,
          createdAt = LocalDateTime.now(utcZone))

  @Test
  fun `subject should be singular for one item`() {
    assertEquals("Dallas Makerspace digest: 1 update", DigestBodyBuilder.subject(1))
  }

  @Test
  fun `subject should be plural for multiple items`() {
    assertEquals("Dallas Makerspace digest: 3 updates", DigestBodyBuilder.subject(3))
  }

  @Test
  fun `textBody should list subjects in order`() {
    val items = listOf(notification(1, "First", "<p>one</p>"), notification(2, "Second", null))

    val text = DigestBodyBuilder.textBody(items)

    assertEquals("First\nSecond", text)
  }

  @Test
  fun `htmlBody should concatenate stored bodies in order`() {
    val items =
        listOf(notification(1, "First", "<p>one</p>"), notification(2, "Second", "<p>two</p>"))

    val html = DigestBodyBuilder.htmlBody(items)

    assertTrue(html.contains("<p>one</p>"))
    assertTrue(html.contains("<p>two</p>"))
    assertTrue(html.indexOf("<p>one</p>") < html.indexOf("<p>two</p>"))
  }

  @Test
  fun `htmlBody should fall back to subject when body missing`() {
    val html = DigestBodyBuilder.htmlBody(listOf(notification(1, "Only subject", null)))

    assertTrue(html.contains("Only subject"))
    assertTrue(html.contains("Here are your updates from Dallas Makerspace"))
  }

  @Test
  fun `htmlBody should handle empty list`() {
    val html = DigestBodyBuilder.htmlBody(emptyList())

    assertTrue(html.contains("Here are your updates from Dallas Makerspace"))
  }
}

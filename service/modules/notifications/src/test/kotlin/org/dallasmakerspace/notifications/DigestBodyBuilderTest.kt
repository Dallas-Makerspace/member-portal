package org.dallasmakerspace.notifications

import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.dallasmakerspace.notifications.channels.OutgoingMessage
import org.dallasmakerspace.notifications.topics.FollowingTopic
import org.dallasmakerspace.notifications.topics.SystemTopic
import org.junit.Test

class DigestBodyBuilderTest {

  private val utcZone = ZoneId.of("UTC")

  private fun notification(
      id: Long,
      subject: String,
      body: String?,
      topic: NotificationTopic = SystemTopic,
      aggregation: Aggregation = Aggregation.DAILY,
  ) =
      Notification(
          id = id,
          memberUsername = "jdoe",
          topic = topic,
          channel = Channel.EMAIL,
          aggregation = aggregation,
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
  fun `subject should be singular for one item and name the cadence`() {
    assertEquals(
        "Your Dallas Makerspace daily digest: 1 update",
        DigestBodyBuilder.subject(listOf(notification(1, "A", "<p>a</p>"))))
  }

  @Test
  fun `subject should count distinct items only`() {
    val items =
        listOf(
            notification(1, "Same", "<p>same</p>", aggregation = Aggregation.WEEKLY),
            notification(2, "Same", "<p>same</p>", aggregation = Aggregation.WEEKLY),
            notification(3, "Other", "<p>other</p>", aggregation = Aggregation.WEEKLY))

    assertEquals("Your Dallas Makerspace weekly digest: 2 updates", DigestBodyBuilder.subject(items))
  }

  @Test
  fun `subject should omit cadence when items mix daily and weekly`() {
    val items =
        listOf(
            notification(1, "A", "<p>a</p>", aggregation = Aggregation.DAILY),
            notification(2, "B", "<p>b</p>", aggregation = Aggregation.WEEKLY))

    assertEquals("Your Dallas Makerspace digest: 2 updates", DigestBodyBuilder.subject(items))
  }

  @Test
  fun `textBody should use each item's rendered text grouped by topic`() {
    val items = listOf(notification(1, "First", "<p>one</p>"), notification(2, "Second", null))

    val text = DigestBodyBuilder.textBody(items) { "text of ${it.subject}" }

    assertTrue(text.startsWith("== System =="))
    assertTrue(text.indexOf("text of First") < text.indexOf("text of Second"))
    assertTrue(!text.contains("<p>"))
  }

  @Test
  fun `htmlBody should keep stored bodies in order`() {
    val items =
        listOf(notification(1, "First", "<p>one</p>"), notification(2, "Second", "<p>two</p>"))

    val html = DigestBodyBuilder.htmlBody(items)

    assertTrue(html.indexOf("<p>one</p>") < html.indexOf("<p>two</p>"))
  }

  @Test
  fun `htmlBody should show duplicate items once`() {
    val items =
        listOf(notification(1, "Same", "<p>same</p>"), notification(2, "Same", "<p>same</p>"))

    val html = DigestBodyBuilder.htmlBody(items)

    assertEquals(1, Regex("<p>same</p>").findAll(html).count())
  }

  @Test
  fun `htmlBody should add a heading per topic`() {
    val items =
        listOf(
            notification(1, "Event", "<p>event</p>", topic = FollowingTopic),
            notification(2, "Sys", "<p>sys</p>"))

    val html = DigestBodyBuilder.htmlBody(items)

    assertTrue(html.contains(">Updates from people you follow</h2>"))
    assertTrue(html.contains(">System</h2>"))
  }

  @Test
  fun `htmlBody should fall back to escaped subject when body missing`() {
    val html = DigestBodyBuilder.htmlBody(listOf(notification(1, "Tom & Jerry", null)))

    assertTrue(html.contains("<p>Tom &amp; Jerry</p>"))
  }

  @Test
  fun `combined should send a single item exactly as rendered`() {
    val message =
        DigestBodyBuilder.combined(listOf(notification(1, "Only", "<p>only</p>"))) { "text" }

    assertEquals(OutgoingMessage("Only", "text", "<p>only</p>"), message)
  }

  @Test
  fun `combined should use the topic's subject and skip headings for one topic`() {
    val items =
        listOf(
            notification(1, "A", "<p>a</p>", topic = FollowingTopic),
            notification(2, "B", "<p>b</p>", topic = FollowingTopic))

    val message = DigestBodyBuilder.combined(items) { it.subject }

    assertEquals("2 new updates from members you follow", message.subject)
    assertTrue(!message.html.contains("<h2"))
    assertTrue(message.html.indexOf("<p>a</p>") < message.html.indexOf("<p>b</p>"))
  }

  @Test
  fun `combined should use a generic subject and headings across topics`() {
    val items =
        listOf(
            notification(1, "A", "<p>a</p>", topic = FollowingTopic),
            notification(2, "B", "<p>b</p>"))

    val message = DigestBodyBuilder.combined(items) { it.subject }

    assertEquals("2 new updates from Dallas Makerspace", message.subject)
    assertTrue(message.html.contains(">Updates from people you follow</h2>"))
  }

  @Test
  fun `withNote should append to both parts`() {
    val message =
        DigestBodyBuilder.withNote(OutgoingMessage("s", "body", "<p>body</p>"), "note", "<b>note</b>")

    assertEquals("body\n\nnote", message.text)
    assertTrue(message.html.startsWith("<p>body</p>") && message.html.contains("<b>note</b>"))
  }
}

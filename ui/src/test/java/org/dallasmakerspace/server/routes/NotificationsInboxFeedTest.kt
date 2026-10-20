package org.dallasmakerspace.server.routes

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationsInboxFeedTest {
  // 2026-11-05 is a Thursday; service timestamps are UTC, the feed shows Central.
  private val today = LocalDate.of(2026, 11, 5)

  private fun row(
      id: Int,
      status: String = "SENT",
      sentAt: String? = "2026-11-05T15:00:00",
      readAt: String? = null,
      topic: String = "COMMITTEE_UPDATES",
      subject: String = "New class from Laser: Intro to Laser Cutting",
      providerId: String? = null,
      aggregation: String = "IMMEDIATE",
      nextAttemptAt: String? = null,
      card: Map<String, Any?>? =
          mapOf(
              "kind" to "class",
              "title" to "Intro to Laser Cutting",
              "excerpt" to "Learn to run the laser cutter",
              "url" to "https://example.test/events/$id",
              "ctaLabel" to "View class",
              "sender" to "Laser",
              "whenLine" to "Sat Nov 8 10:00 AM"),
  ): Map<String, Any?> =
      mapOf(
          "id" to id,
          "status" to status,
          "sentAt" to sentAt,
          "createdAt" to "2026-11-05T14:55:00",
          "readAt" to readAt,
          "topic" to topic,
          "topicDisplayName" to "Updates from committees you follow",
          "subject" to subject,
          "providerId" to providerId,
          "aggregation" to aggregation,
          "nextAttemptAt" to nextAttemptAt,
          "card" to card)

  @Suppress("UNCHECKED_CAST")
  private fun items(feed: Map<String, Any?>): List<Map<String, Any?>> =
      (feed["groups"] as List<Map<String, Any?>>).flatMap { it["items"] as List<Map<String, Any?>> }

  @Suppress("UNCHECKED_CAST")
  private fun labels(feed: Map<String, Any?>): List<String> =
      (feed["groups"] as List<Map<String, Any?>>).map { it["label"] as String }

  @Test
  fun `unread is delivered and not yet opened, and counted per card`() {
    val feed =
        buildInboxFeed(
            listOf(
                row(1),
                row(2, readAt = "2026-11-04T10:00:00"),
                row(3, status = "QUEUED", sentAt = null)),
            today)

    assertEquals(listOf(true, false, false), items(feed).map { it["unread"] })
    assertEquals(1, feed["new_count"])
    assertEquals(3, feed["total_count"])
  }

  @Test
  fun `cards carry the service card, with the subject as the fallback title`() {
    val feed = buildInboxFeed(listOf(row(1), row(2, card = null, subject = "Test notification: hi")), today)

    val first = items(feed)[0]
    assertEquals("Intro to Laser Cutting", first["title"])
    assertEquals("class", first["kind"])
    assertEquals("View class", first["ctaLabel"])
    assertEquals("Laser", first["sender"])
    assertEquals("Sat Nov 8 10:00 AM", first["whenLine"])
    val plain = items(feed)[1]
    assertEquals("Test notification: hi", plain["title"])
    assertEquals("", plain["url"])
    assertEquals("", plain["ctaLabel"])
    assertEquals("message", plain["kind"])
  }

  @Test
  fun `only http links are used`() {
    val bad = mapOf("title" to "x", "url" to "javascript:alert(1)", "ctaLabel" to "Open")
    val feed = buildInboxFeed(listOf(row(1, card = bad)), today)

    assertEquals("", items(feed)[0]["url"])
    assertEquals("", items(feed)[0]["ctaLabel"])
  }

  @Test
  fun `cards are grouped by Central day`() {
    val feed =
        buildInboxFeed(
            listOf(
                row(1, sentAt = "2026-11-05T15:00:00"),
                row(2, sentAt = "2026-11-04T15:00:00"),
                row(3, sentAt = "2026-11-01T15:00:00"),
                row(4, sentAt = "2026-10-01T15:00:00")),
            today)

    assertEquals(listOf("Today", "Yesterday", "Earlier this week", "Earlier"), labels(feed))
  }

  @Test
  fun `a late-evening Central send is still today`() {
    // 03:30 UTC on Nov 6 is 9:30 PM Central on Nov 5.
    val feed = buildInboxFeed(listOf(row(1, sentAt = "2026-11-06T03:30:00")), today)

    assertEquals(listOf("Today"), labels(feed))
  }

  @Test
  fun `rows mailed together become one digest bundle with a preview`() {
    val feed =
        buildInboxFeed(
            listOf(
                row(1, providerId = "p1", aggregation = "DAILY").plus("card" to mapOf("title" to "A", "url" to "https://example.test/a")),
                row(2, providerId = "p1", aggregation = "DAILY", readAt = "2026-11-05T16:00:00")
                    .plus("card" to mapOf("title" to "B")),
                row(3, providerId = "p1", aggregation = "DAILY").plus("card" to mapOf("title" to "C"))),
            today)

    val digest = items(feed).single()
    assertEquals(true, digest["isDigest"])
    assertEquals("Daily digest · 3 updates", digest["title"])
    assertEquals("A, B +1 more", digest["preview"])
    assertEquals(3, digest["childCount"])
    assertEquals(true, digest["unread"])
    assertEquals(1, feed["new_count"])
  }

  @Test
  fun `delivery notes appear only when there is something to say`() {
    val feed =
        buildInboxFeed(
            listOf(
                row(1),
                row(2, status = "IN_DIGEST", sentAt = null, nextAttemptAt = "2026-11-06T14:00:00"),
                row(3, status = "FAILED", sentAt = null),
                row(4, status = "SUPPRESSED", sentAt = null)),
            today)

    val byId = items(feed).associateBy { it["id"] }
    assertEquals(null, byId[1]!!["note"])
    assertEquals("In next digest (Fri, Nov 6 at 8:00 AM)", byId[2]!!["note"])
    assertEquals(false, byId[2]!!["noteProblem"])
    assertEquals("Retrying", byId[3]!!["note"])
    assertEquals(true, byId[3]!!["noteProblem"])
    assertEquals("Paused", byId[4]!!["note"])
    assertEquals(true, byId[4]!!["noteProblem"])
  }

  @Test
  fun `skipped rows are hidden from members but shown to admins`() {
    val rows = listOf(row(1), row(2, status = "SKIPPED", sentAt = null))

    assertEquals(1, items(buildInboxFeed(rows, today)).size)
    assertEquals(2, items(buildInboxFeed(rows, today, showErrors = true)).size)
  }

  @Test
  fun `filters keep one topic and report whether anything exists at all`() {
    val rows =
        listOf(
            row(1, topic = "COMMITTEE_UPDATES"),
            row(2, topic = "EVENT_ANNOUNCEMENT"),
            row(3, topic = "EVENT_ANNOUNCEMENT"))

    val people = buildInboxFeed(rows, today, filter = "people")
    assertEquals(listOf(2, 3), items(people).map { it["id"] })
    assertTrue(people["has_any"] == true)

    val committees = buildInboxFeed(rows, today, filter = "committees")
    assertEquals(listOf(1), items(committees).map { it["id"] })

    val unknown = buildInboxFeed(rows, today, filter = "nonsense")
    assertEquals(3, items(unknown).size)
  }

  @Test
  fun `an empty feed has nothing and a filtered-empty feed still has_any`() {
    val empty = buildInboxFeed(emptyList(), today)
    assertFalse(empty["has_any"] == true)
    assertEquals(0, empty["total_count"])

    val none = buildInboxFeed(listOf(row(1, topic = "COMMITTEE_UPDATES")), today, filter = "people")
    assertTrue(none["has_any"] == true)
    assertEquals(0, none["total_count"])
  }

  @Test
  fun `positions count cards so the template can page them`() {
    val feed = buildInboxFeed((1..30).map { row(it) }, today)

    assertEquals((1..30).toList(), items(feed).map { it["position"] })
    assertEquals(INBOX_PAGE_SIZE, feed["page_size"])
  }
}

package org.dallasmakerspace.server.routes

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.dallasmakerspace.server.auth.Authz
import org.thymeleaf.TemplateEngine
import org.thymeleaf.context.Context
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver

/** Renders the real inbox template over feeds built by [buildInboxFeed]. */
class NotificationsInboxTemplateTest {
  private val engine =
      TemplateEngine().apply {
        setTemplateResolver(
            ClassLoaderTemplateResolver().apply {
              prefix = "templates/"
              suffix = ".html"
              characterEncoding = "UTF-8"
            })
      }

  private val today = LocalDate.of(2026, 11, 5)

  private fun row(
      id: Int,
      status: String = "SENT",
      readAt: String? = null,
      providerId: String? = null,
      topic: String = "COMMITTEE_UPDATES",
      card: Map<String, Any?>? =
          mapOf(
              "kind" to "class",
              "title" to "Intro to Laser Cutting $id",
              "excerpt" to "Learn to run the laser cutter",
              "url" to "https://example.test/events/$id",
              "ctaLabel" to "View class",
              "sender" to "Laser",
              "whenLine" to "Sat Nov 8 10:00 AM",
              "imageUrl" to "https://example.test/i$id.jpg"),
  ): Map<String, Any?> =
      mapOf(
          "id" to id,
          "status" to status,
          "sentAt" to "2026-11-05T15:00:00",
          "createdAt" to "2026-11-05T14:55:00",
          "readAt" to readAt,
          "topic" to topic,
          "topicDisplayName" to "Updates from committees you follow",
          "subject" to "New class from Laser: Intro $id",
          "providerId" to providerId,
          "aggregation" to "DAILY",
          "lastError" to "provider said no",
          "card" to card)

  private fun render(
      rows: List<Map<String, Any?>>,
      filter: String = "all",
      suppressed: Boolean = false,
      viewingOther: Boolean = false,
      showErrors: Boolean = false,
  ): String {
    val feed = buildInboxFeed(rows, today, filter.takeIf { it != "all" }, showErrors)
    val context =
        Context().apply {
          setVariables(
              feed +
                  mapOf(
                      "preferred_username" to "jdoe",
                      "authz" to Authz(emptySet()),
                      "suppressed" to suppressed,
                      "viewing_other" to viewingOther,
                      "filter" to filter))
        }
    return engine.process("notifications", context)
  }

  private fun String.flat(): String = replace(Regex("\\s+"), " ")

  @Test
  fun `a card is one stretched title link with hidden New text and a decorative CTA`() {
    val html = render(listOf(row(1)))

    assertTrue(Regex("<a[^>]*class=\"inbox-link stretched-link\"[^>]*href=\"https://example.test/events/1\"")
        .containsMatchIn(html.flat()) ||
        Regex("<a[^>]*href=\"https://example.test/events/1\"[^>]*class=\"inbox-link stretched-link\"")
            .containsMatchIn(html.flat()))
    assertTrue(html.contains("New: "))
    assertTrue(html.flat().contains("<span class=\"inbox-cta\" aria-hidden=\"true\">View class ›</span>"))
    assertTrue(html.contains("is-unread"))
    assertTrue(html.contains("alt=\"\""))
    assertEquals(1, Regex("href=\"https://example.test/events/1\"").findAll(html).count())
  }

  @Test
  fun `a read card has no unread treatment`() {
    val html = render(listOf(row(1, readAt = "2026-11-05T16:00:00")))

    assertFalse(html.contains("is-unread"))
    assertFalse(html.contains("New: "))
    assertFalse(html.contains("inbox-new-pill"))
  }

  @Test
  fun `the header says how many are new`() {
    val html = render(listOf(row(1), row(2), row(3, readAt = "2026-11-05T16:00:00")))

    assertTrue(html.flat().contains("2 new since your last visit"))
  }

  @Test
  fun `with nothing new the header says you are caught up`() {
    val html = render(listOf(row(1, readAt = "2026-11-05T16:00:00")))

    assertTrue(html.contains("You're all caught up"))
    assertFalse(html.contains("inbox-new-pill"))
    assertFalse(render(emptyList()).contains("all caught up"))
  }

  @Test
  fun `card titles are headings, one level below a bundle's heading inside it`() {
    val html =
        render(
            listOf(
                row(1),
                row(2, providerId = "p"),
                row(3, providerId = "p")))

    assertTrue(Regex("role=\"heading\"[^>]*aria-level=\"3\"|aria-level=\"3\"[^>]*role=\"heading\"")
        .containsMatchIn(html))
    assertTrue(Regex("role=\"heading\"[^>]*aria-level=\"4\"|aria-level=\"4\"[^>]*role=\"heading\"")
        .containsMatchIn(html))
  }

  @Test
  fun `a capped feed says it is the latest updates`() {
    val feed = buildInboxFeed((1..30).map { row(it) }, today, capped = true)
    val context =
        Context().apply {
          setVariables(
              feed +
                  mapOf(
                      "preferred_username" to "jdoe",
                      "authz" to Authz(emptySet()),
                      "suppressed" to false,
                      "viewing_other" to false,
                      "filter" to "all"))
        }

    assertTrue(engine.process("notifications", context).contains("Showing 25 of your latest 30 updates"))
  }

  @Test
  fun `a notification without a link renders its title as plain text`() {
    val html = render(listOf(row(1, card = null)))

    assertFalse(html.contains("stretched-link"))
    assertTrue(html.contains("New class from Laser: Intro 1"))
  }

  @Test
  fun `a digest is a disclosure button with its items inside`() {
    val html =
        render(listOf(row(1, providerId = "p"), row(2, providerId = "p"), row(3, providerId = "p")))

    assertTrue(html.contains("Daily digest · 3 updates"))
    assertTrue(Regex("<button[^>]*aria-expanded=\"false\"").containsMatchIn(html))
    assertTrue(Regex("aria-controls=\"digest-1\"").containsMatchIn(html))
    assertTrue(html.contains("id=\"digest-1\""))
    assertEquals(3, Regex("class=\"inbox-link stretched-link\"").findAll(html).count())
  }

  @Test
  fun `filter chips are links and the active one is marked current`() {
    val html = render(listOf(row(1)), filter = "committees")

    assertTrue(html.contains("/profile/@jdoe/notifications?filter=people"))
    assertEquals(1, Regex("aria-current=\"page\"").findAll(html).count())
    assertTrue(Regex("is-active\"[^>]*>Committees").containsMatchIn(html.flat()) ||
        html.flat().contains("Committees</a>"))
  }

  @Test
  fun `an inbox that has never had anything invites following`() {
    val html = render(emptyList())

    assertTrue(html.contains("Nothing new yet"))
    assertTrue(html.contains("href=\"/committees\""))
    assertFalse(html.contains("Filter notifications"))
  }

  @Test
  fun `a filtered view with nothing in it offers to show everything`() {
    val html = render(listOf(row(1, topic = "COMMITTEE_UPDATES")), filter = "people")

    assertTrue(html.contains("No updates in this view"))
    assertTrue(html.contains("Show all notifications"))
    assertFalse(html.contains("Nothing new yet"))
  }

  @Test
  fun `paused emails show a status banner`() {
    val html = render(listOf(row(1)), suppressed = true)

    assertTrue(Regex("inbox-paused\"[^>]*role=\"status\"|role=\"status\"[^>]*inbox-paused")
        .containsMatchIn(html.flat()))
    assertTrue(html.flat().contains("Email notifications are paused"))
  }

  @Test
  fun `an admin viewing another inbox is told nothing is marked read`() {
    val html = render(listOf(row(1)), viewingOther = true)

    assertTrue(html.flat().contains("(read-only)"))
    assertFalse(html.contains("since your last visit"))
    assertFalse(html.contains("all caught up"))
    assertFalse(render(listOf(row(1))).contains("(read-only)"))
  }

  @Test
  fun `only the first page is visible and Load more reveals the rest`() {
    val html = render((1..30).map { row(it) })

    assertEquals(5, Regex("inbox-item inbox-extra").findAll(html).count())
    assertTrue(html.contains("id=\"inboxLoadMore\""))
    assertTrue(html.contains("Showing 25 of 30 updates"))
  }

  @Test
  fun `a short feed has no Load more`() {
    val html = render((1..5).map { row(it) })

    assertFalse(html.contains("inboxLoadMore"))
    assertFalse(html.contains("inbox-extra"))
  }

  @Test
  fun `delivery problems are written out and raw errors are admin only`() {
    val rows = listOf(row(1, status = "FAILED"))

    val member = render(rows)
    assertTrue(member.contains("Retrying"))
    assertFalse(member.contains("provider said no"))
    assertTrue(render(rows, showErrors = true).contains("provider said no"))
  }
}

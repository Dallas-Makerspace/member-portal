package org.dallasmakerspace.server.routes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.thymeleaf.TemplateEngine
import org.thymeleaf.context.Context
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver

/** Renders the real "Events Organized" fragment, since its expand behaviour lives in attributes. */
class EventsOrganizedCardTemplateTest {
  private val engine =
      TemplateEngine().apply {
        setTemplateResolver(
            ClassLoaderTemplateResolver().apply {
              prefix = "templates/"
              suffix = ".html"
              characterEncoding = "UTF-8"
            })
      }

  private fun event(id: Int, upcoming: Boolean) =
      mapOf<String, Any?>(
          "id" to id,
          "name" to "Event $id",
          "eventStart" to "Sat, Nov 7, 2026 at 7:00 PM",
          "eventStartRaw" to "2026-11-07 19:00:00",
          "isUpcoming" to upcoming,
          "url" to "https://calendar.example.test/events/view/$id")

  private fun render(upcoming: Int, past: Int): String {
    val events = markCollapsedEvents((1..upcoming).map { event(it, true) } + (100..<(100 + past)).map { event(it, false) })
    val context =
        Context().apply {
          setVariable("events_upcoming", events.filter { it["isUpcoming"] == true })
          setVariable("events_past", events.filter { it["isUpcoming"] != true })
          setVariable("events_hidden_count", events.count { it["collapsed"] == true })
        }
    return engine.process("test-events-organized", context)
  }

  private fun items(html: String) = Regex("<li[^>]*>").findAll(html).map { it.value }.toList()

  @Test
  fun `hidden events carry both the hidden attribute and the marker the script looks for`() {
    val html = render(upcoming = 6, past = 4)

    // Thymeleaf drops empty-string attributes, so the marker must have a value.
    val hiddenItems = items(html).filter { it.contains("hidden=\"hidden\"") }
    assertEquals(5, hiddenItems.size)
    assertTrue(hiddenItems.all { it.contains("data-expandable-extra=\"true\"") }, hiddenItems.toString())
    assertEquals(5, Regex("data-expandable-extra").findAll(html).count())
  }

  @Test
  fun `visible events have neither`() {
    val html = render(upcoming = 6, past = 4)

    assertEquals(10, Regex("href=\"https://calendar.example.test/events/view/").findAll(html).count())
    assertEquals(5, items(html).count { !it.contains("hidden") })
  }

  @Test
  fun `upcoming and past are separate labelled groups in that order`() {
    val html = render(upcoming = 6, past = 4)

    val upcomingHeading = html.indexOf("Upcoming<span")
    val pastHeading = html.indexOf("Recently held")
    assertTrue(upcomingHeading in 0..<pastHeading, "Upcoming must come before Recently held")
    assertTrue(html.contains(" (6)"))
    assertTrue(html.contains("aria-labelledby=\"events-upcoming-heading\""))
    assertTrue(html.contains("aria-labelledby=\"events-past-heading\""))
    // The past events (ids 100+) are all after the past heading, the upcoming ones before it.
    assertTrue(html.indexOf("events/view/100") > pastHeading)
    assertTrue(html.indexOf("events/view/6\"") < pastHeading)
  }

  @Test
  fun `the toggle names the list it controls and says how many are hidden`() {
    val html = render(upcoming = 6, past = 4)

    assertTrue(html.contains("id=\"events-organized\""))
    assertTrue(html.contains("data-expandable"))
    assertTrue(html.contains("id=\"events-organized-list\""))
    assertTrue(html.contains("aria-controls=\"events-organized-list\""))
    assertTrue(html.contains("aria-expanded=\"false\""))
    assertTrue(html.contains("data-more-label=\"Show 5 more\""))
    assertTrue(html.contains("data-less-label=\"Show fewer\""))
    assertTrue(html.contains(">Show 5 more<"))
  }

  @Test
  fun `links announce that they open a new tab and icons are hidden from screen readers`() {
    val html = render(upcoming = 1, past = 1)

    assertEquals(2, Regex("\\(opens in new tab\\)").findAll(html).count())
    assertEquals(4, Regex("material-symbols-outlined[^>]*aria-hidden=\"true\"").findAll(html).count())
  }

  @Test
  fun `a short list has no toggle and nothing hidden`() {
    val html = render(upcoming = 2, past = 2)

    assertFalse(html.contains("data-expandable-toggle"))
    assertFalse(html.contains("hidden=\"hidden\""))
    assertFalse(html.contains("data-expandable-extra"))
  }

  @Test
  fun `with nothing upcoming it says so and still lists the past ones`() {
    val html = render(upcoming = 0, past = 3)

    assertTrue(html.contains("No upcoming events."))
    assertTrue(html.contains("Recently held"))
    assertFalse(html.contains("Upcoming ("))
  }

  @Test
  fun `with nothing past there is no past group`() {
    val html = render(upcoming = 3, past = 0)

    assertFalse(html.contains("Recently held"))
  }
}

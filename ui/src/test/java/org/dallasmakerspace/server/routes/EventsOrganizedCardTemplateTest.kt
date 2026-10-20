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
          setVariable("events_organized", events)
          setVariable("events_hidden_count", events.count { it["collapsed"] == true })
        }
    return engine.process("test-events-organized", context)
  }

  @Test
  fun `hidden events carry both the hidden attribute and the marker the script looks for`() {
    val html = render(upcoming = 6, past = 4)

    // Thymeleaf drops empty-string attributes, so the marker must have a value.
    val hiddenLinks = Regex("<a [^>]*hidden=\"hidden\"[^>]*>").findAll(html).toList()
    assertEquals(5, hiddenLinks.size)
    assertTrue(hiddenLinks.all { it.value.contains("data-expandable-extra=\"true\"") }, hiddenLinks.toString())
    assertEquals(5, Regex("data-expandable-extra").findAll(html).count())
  }

  @Test
  fun `visible events have neither`() {
    val html = render(upcoming = 6, past = 4)

    val visible = Regex("<a [^>]*href=\"[^\"]*events/view/[^\"]*\"[^>]*>").findAll(html).map { it.value }.toList()
    assertEquals(10, visible.size)
    assertEquals(5, visible.count { !it.contains("hidden") })
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
  fun `a short list has no toggle and nothing hidden`() {
    val html = render(upcoming = 2, past = 2)

    assertFalse(html.contains("data-expandable-toggle"))
    assertFalse(html.contains("hidden="))
    assertFalse(html.contains("data-expandable-extra"))
  }
}

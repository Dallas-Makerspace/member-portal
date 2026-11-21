package org.dallasmakerspace.dashboards

import kotlin.test.Test
import kotlin.test.assertEquals

class DashboardPagesTest {
  private val widget = WidgetDto(0, WidgetType.TEXT_IMAGE, WidgetConfig(heading = "Hi"))
  private val shown = PageDto(title = "shown", widgets = listOf(widget))
  private val hidden = PageDto(title = "hidden", enabled = false, widgets = listOf(widget))
  private val empty = PageDto(title = "empty")

  @Test
  fun `a TV rotates through enabled pages that have widgets`() {
    assertEquals(listOf("shown"), pagesToShow(listOf(hidden, shown, empty), null).map { it.title })
  }

  @Test
  fun `a pinned page is shown alone whatever its state`() {
    val pages = listOf(shown, hidden, empty)
    assertEquals(listOf("hidden"), pagesToShow(pages, 1).map { it.title })
    assertEquals(listOf("empty"), pagesToShow(pages, 2).map { it.title })
  }

  @Test
  fun `a pin past the last page shows nothing`() {
    assertEquals(emptyList(), pagesToShow(listOf(shown), 5))
  }

  @Test
  fun `all shows every page in order, hidden and empty ones too`() {
    val pages = listOf(shown, hidden, empty)

    assertEquals(listOf("shown", "hidden", "empty"), pagesToShow(pages, null, all = true).map { it.title })
    assertEquals(listOf("shown"), pagesToShow(pages, null).map { it.title })
  }

  @Test
  fun `an event start comes from the UTC value or else the Central text`() {
    assertEquals(5L, DashboardTime.eventStartMs(5L, "2026-12-02 18:30:00"))
    // 18:30 Central on 2 December is 00:30 UTC on 3 December (standard time, UTC-6)
    assertEquals(1_796_257_800_000L, DashboardTime.eventStartMs(null, "2026-12-02 18:30:00"))
    assertEquals(1_796_257_800_000L, DashboardTime.eventStartMs(null, "2026-12-02T18:30:00"))
    assertEquals(null, DashboardTime.eventStartMs(null, ""))
    assertEquals(null, DashboardTime.eventStartMs(null, "soon"))
    assertEquals(null, DashboardTime.eventStartMs(null, null))
  }
}

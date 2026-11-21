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
  fun `a saved page is found by its id, and an unsaved one is not`() {
    val first = shown.copy(id = 11)
    val second = hidden.copy(id = 12)

    assertEquals(0, pinIndexOf(listOf(first, second), 11))
    assertEquals(1, pinIndexOf(listOf(first, second), 12))
    assertEquals(null, pinIndexOf(listOf(first, second), 99))
    assertEquals(null, pinIndexOf(listOf(first, empty), 12))
  }
}

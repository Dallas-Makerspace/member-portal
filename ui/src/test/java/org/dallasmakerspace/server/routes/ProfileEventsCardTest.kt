package org.dallasmakerspace.server.routes

import kotlin.test.Test
import kotlin.test.assertEquals

class ProfileEventsCardTest {
  private fun upcoming(id: Int) = mapOf<String, Any?>("id" to id, "isUpcoming" to true)

  private fun past(id: Int) = mapOf<String, Any?>("id" to id, "isUpcoming" to false)

  private fun hidden(events: List<Map<String, Any?>>): List<Any?> =
      markCollapsedEvents(events).filter { it["collapsed"] == true }.map { it["id"] }

  private fun visible(events: List<Map<String, Any?>>): List<Any?> =
      markCollapsedEvents(events).filter { it["collapsed"] == false }.map { it["id"] }

  @Test
  fun `a short list is fully visible`() {
    val events = listOf(upcoming(1), upcoming(2), past(3), past(4), past(5))

    assertEquals(emptyList<Any?>(), hidden(events))
  }

  @Test
  fun `collapsed shows the next upcoming events and keeps room for the latest past ones`() {
    val events = (1..6).map(::upcoming) + (7..11).map(::past)

    assertEquals(listOf(1, 2, 3, 7, 8), visible(events))
    assertEquals(listOf(4, 5, 6, 9, 10, 11), hidden(events))
  }

  @Test
  fun `with few past events upcoming ones use the free slots`() {
    val events = (1..8).map(::upcoming) + listOf(past(9))

    assertEquals(listOf(1, 2, 3, 4, 9), visible(events))
  }

  @Test
  fun `with no upcoming events it is the latest past ones`() {
    val events = (1..8).map(::past)

    assertEquals(listOf(1, 2, 3, 4, 5), visible(events))
    assertEquals(3, hidden(events).size)
  }

  @Test
  fun `with no past events it is the next upcoming ones`() {
    val events = (1..8).map(::upcoming)

    assertEquals(listOf(1, 2, 3, 4, 5), visible(events))
  }

  @Test
  fun `the reading order is kept so hidden ones expand in place`() {
    val events = (1..6).map(::upcoming) + (7..9).map(::past)

    assertEquals((1..9).toList(), markCollapsedEvents(events).map { it["id"] })
  }

  @Test
  fun `no events means nothing to mark`() {
    assertEquals(emptyList(), markCollapsedEvents(emptyList()))
  }
}

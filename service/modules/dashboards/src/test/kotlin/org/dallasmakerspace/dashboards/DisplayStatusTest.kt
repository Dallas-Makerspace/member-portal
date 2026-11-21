package org.dallasmakerspace.dashboards

import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DisplayStatusTest {
  @Test
  fun `a TV never reported on is not seen yet`() {
    assertEquals(DisplayStatus.Summary(null, "Not seen yet", false), DisplayStatus.summarize(null))
    assertEquals(DisplayStatus.Summary(null, "Not seen yet", false), DisplayStatus.summarize("  "))
  }

  @Test
  fun `the agent's reports become plain wording`() {
    assertEquals(DisplayStatus.Summary(false, "Offline", false), DisplayStatus.summarize("offline"))
    assertEquals(DisplayStatus.Summary(true, "Idle (nothing is casting)", false), DisplayStatus.summarize("idle"))
    assertEquals(DisplayStatus.Summary(true, "The committee dashboard", true), DisplayStatus.summarize("Dashboard"))
    assertEquals(
        DisplayStatus.Summary(true, "The committee dashboard", true),
        DisplayStatus.summarize("DMS Committee Dashboard"))
    assertEquals(DisplayStatus.Summary(true, "Showing YouTube", false), DisplayStatus.summarize("YouTube"))
  }

  @Test
  fun `a daytime schedule says when it ends or starts`() {
    assertEquals(true to "On until 9:30 PM", DisplayStatus.schedule(LocalTime.of(12, 0), "08:00", "21:30"))
    assertEquals(false to "Off until 8:00 AM", DisplayStatus.schedule(LocalTime.of(22, 0), "08:00", "21:30"))
  }

  @Test
  fun `an overnight schedule is on through midnight`() {
    assertEquals(true to "On until 2:00 AM", DisplayStatus.schedule(LocalTime.of(23, 30), "07:00", "02:00"))
    assertEquals(true to "On until 2:00 AM", DisplayStatus.schedule(LocalTime.of(1, 59), "07:00", "02:00"))
    assertEquals(false to "Off until 7:00 AM", DisplayStatus.schedule(LocalTime.of(2, 0), "07:00", "02:00"))
    assertEquals(false to "Off until 7:00 AM", DisplayStatus.schedule(LocalTime.of(6, 59), "07:00", "02:00"))
  }

  @Test
  fun `no hours means always on`() {
    assertEquals(true to "Always on", DisplayStatus.schedule(LocalTime.of(3, 0), null, null))
    assertTrue(DisplayStatus.schedule(LocalTime.of(3, 0), "", "").first)
    assertFalse(DisplayStatus.schedule(LocalTime.of(3, 0), "08:00", "09:00").first)
    assertNull(DisplayStatus.summarize(null).online)
  }

  @Test
  fun `a set up TV has a badge state`() {
    assertEquals("DISABLED", DisplayStatus.state(false, true))
    assertEquals("ONLINE", DisplayStatus.state(true, true))
    assertEquals("OFFLINE", DisplayStatus.state(true, false))
    assertEquals("UNSEEN", DisplayStatus.state(true, null))
  }

  @Test
  fun `a device only the sweep knows is on the network for two hours after it was seen`() {
    val now = java.time.LocalDateTime.of(2026, 11, 21, 12, 0)
    assertEquals("ON_NETWORK", DisplayStatus.discoveredState(now.minusMinutes(90), now))
    assertEquals("ON_NETWORK", DisplayStatus.discoveredState(now.minusHours(2), now))
    assertEquals("AWAY", DisplayStatus.discoveredState(now.minusHours(2).minusMinutes(1), now))
  }
}

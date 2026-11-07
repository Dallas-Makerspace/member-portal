package org.dallasmakerspace.dataviz.reports.interlock

import kotlin.test.assertEquals
import org.junit.Test

class FormatEventTimeTest {
  @Test
  fun `a log timestamp becomes a short Central time`() {
    assertEquals("Fri, Nov 6, 6:53 PM", formatEventTime("2026-11-07T00:53:30.000Z"))
    assertEquals("Thu, Jul 2, 5:00 PM", formatEventTime("2026-07-02T22:00:00Z"))
    assertEquals("Fri, Nov 6, 6:53 PM", formatEventTime("2026-11-07T00:53:30+00:00"))
  }

  @Test
  fun `a timestamp that cannot be read is shown as given`() {
    assertEquals("not a time", formatEventTime("not a time"))
  }
}

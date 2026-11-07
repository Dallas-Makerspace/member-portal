package org.dallasmakerspace.agenttools.activity

import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class ActivityLinksTest {
  private val now = Instant.parse("2026-11-07T15:00:00Z")
  private val thursday = ActivityWindow.parse("2026-11-05T17:00", "2026-11-05T23:59", now)

  @Test
  fun `a door link carries the door and the window`() {
    assertEquals(
        "/reports/member-visits/badge-swipes?doors=102+Server-In&from=2026-11-05T17:00&to=2026-11-05T23:59",
        ActivityLinks.swipes("102 Server-In", thursday).url)
  }

  @Test
  fun `a tool link reaches back far enough to include the window`() {
    assertEquals("/interlock-tools?tool=auto-lift-1&days=2", ActivityLinks.usage(LIFT, thursday, now)?.url)
  }

  @Test
  fun `a window older than the tool page can show has no tool link`() {
    assertNull(ActivityLinks.usage(LIFT, thursday, now.plusSeconds(100L * 86_400)))
  }
}

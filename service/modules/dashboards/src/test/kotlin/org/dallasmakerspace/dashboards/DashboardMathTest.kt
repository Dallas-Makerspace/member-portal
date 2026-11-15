package org.dallasmakerspace.dashboards

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class DashboardMathTest {
  private val now = LocalDateTime.of(2026, 3, 1, 12, 0)

  @Test
  fun `trend undoes recent changes week by week`() {
    val changes =
        listOf(
            now.minusDays(1) to 1,
            now.minusDays(2) to 1,
            now.minusDays(9) to 1,
            now.minusDays(10) to -1,
            now.minusDays(25) to 1)

    val trend = DashboardMath.weeklyTrend(current = 20, changes = changes, now = now, weeks = 4)

    // 20 now; 18 a week ago (2 recent adds); flat across last week's add+remove; 17 before day 25
    assertEquals(listOf(17, 18, 18, 18, 20), trend)
  }

  @Test
  fun `trend never goes below zero`() {
    val trend =
        DashboardMath.weeklyTrend(
            current = 1, changes = listOf(now.minusDays(1) to 1, now.minusDays(2) to 1), now = now, weeks = 2)

    assertEquals(listOf(0, 0, 1), trend)
  }

  @Test
  fun `trend with no history is flat`() {
    assertEquals(List(5) { 7 }, DashboardMath.weeklyTrend(7, emptyList(), now, weeks = 4))
  }

  @Test
  fun `next change is the soonest future time, capped`() {
    assertEquals(150L, DashboardMath.nextChangeAt(100, listOf(50, 300, 150), capMs = 1000))
    assertEquals(1100L, DashboardMath.nextChangeAt(100, listOf(50, 5000), capMs = 1000))
    assertEquals(1100L, DashboardMath.nextChangeAt(100, emptyList(), capMs = 1000))
  }
}

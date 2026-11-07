package org.dallasmakerspace.dataviz.reports.accesscontrol

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test

class BadgeSwipesFilterTest {
  private fun where(vararg pairs: Pair<String, List<String>>) = BadgeSwipesFilter.parse(mapOf(*pairs)).whereClause()

  @Test
  fun `no filter keeps the recent window`() {
    assertEquals("e.date >= DATE_SUB(NOW(), INTERVAL 72 HOUR)", where())
  }

  @Test
  fun `doors and a Central window become a UTC query`() {
    assertEquals(
        "e.date >= '2026-11-05 23:00:00' AND e.date <= '2026-11-06 05:59:00' AND s.name IN ('Garage-In','N/A-In')",
        where(
            "doors" to listOf("Garage-In", "N/A-In"),
            "from" to listOf("2026-11-05T17:00"),
            "to" to listOf("2026-11-05T23:59")))
  }

  @Test
  fun `a start with no end still has a limit`() {
    assertEquals(
        "e.date >= '2026-01-05 06:00:00' AND e.date <= '2026-02-05 06:00:00'",
        where("from" to listOf("2026-01-05T00:00")))
  }

  @Test
  fun `anything that could break out of the query is refused`() {
    assertFailsWith<IllegalArgumentException> { where("doors" to listOf("x' OR '1'='1")) }
    assertFailsWith<IllegalArgumentException> { where("from" to listOf("2026-11-05' OR 1=1 --")) }
  }

  @Test
  fun `bad windows are refused`() {
    assertFailsWith<IllegalArgumentException> { where("to" to listOf("2026-11-05T17:00")) }
    assertFailsWith<IllegalArgumentException> {
      where("from" to listOf("2026-11-05T17:00"), "to" to listOf("2026-11-05T16:00"))
    }
    assertFailsWith<IllegalArgumentException> {
      where("from" to listOf("2026-01-01T00:00"), "to" to listOf("2026-11-05T00:00"))
    }
  }
}

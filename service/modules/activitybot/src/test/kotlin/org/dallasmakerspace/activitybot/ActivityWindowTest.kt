package org.dallasmakerspace.activitybot

import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test

class ActivityWindowTest {
  private val now = Instant.parse("2026-11-07T15:00:00Z")

  @Test
  fun `a Thursday evening window is read in Central time`() {
    val window = ActivityWindow.parse("2026-11-05T17:00", "2026-11-05T23:59", now)

    assertEquals(Instant.parse("2026-11-05T23:00:00Z"), window.from)
    assertEquals(Instant.parse("2026-11-06T05:59:00Z"), window.to)
    assertEquals("17:00", window.clock(window.from))
  }

  @Test
  fun `a date alone is the whole day`() {
    val window = ActivityWindow.parse("2026-11-05", "2026-11-05", now)

    assertEquals(Instant.parse("2026-11-05T06:00:00Z"), window.from)
    assertEquals(Instant.parse("2026-11-06T06:00:00Z"), window.to)
  }

  @Test
  fun `daylight saving time moves the offset`() {
    val summer = ActivityWindow.parse("2026-07-02T17:00", "2026-07-02T20:00", now)

    assertEquals(Instant.parse("2026-07-02T22:00:00Z"), summer.from)
  }

  @Test
  fun `a window that runs into the future is cut at now`() {
    val window = ActivityWindow.parse("2026-11-07", "2026-11-07", now)

    assertEquals(now, window.to)
  }

  @Test
  fun `a multi day window shows the day with each time`() {
    val window = ActivityWindow.parse("2026-11-04T08:00", "2026-11-05T20:00", now)

    assertTrue(window.clock(window.from).startsWith("Wed"))
  }

  @Test
  fun `bad windows are refused with a message`() {
    listOf(
            Triple("yesterday", "2026-11-05", "from must look like"),
            Triple("2026-11-05", "", "to must look like"),
            Triple("2026-11-05T20:00", "2026-11-05T17:00", "after its start"),
            Triple("2026-10-01", "2026-11-05", "at most 7 days"),
            Triple("2026-11-08", "2026-11-09", "future"),
        )
        .forEach { (from, to, message) ->
          val error = assertFailsWith<IllegalArgumentException>("$from..$to") { ActivityWindow.parse(from, to, now) }
          assertTrue(error.message!!.contains(message), error.message)
        }
  }
}

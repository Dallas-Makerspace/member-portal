package org.dallasmakerspace.notifications

import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class DigestScheduleTest {

  // August: Central is UTC-5, so 8:00 AM Central = 13:00 UTC.

  @Test
  fun `daily digest before the window is due the same morning`() {
    assertEquals(
        LocalDateTime.of(2026, 8, 12, 13, 0),
        DigestSchedule.nextDelivery(Aggregation.DAILY, LocalDateTime.of(2026, 8, 12, 12, 0)))
  }

  @Test
  fun `daily digest after the window is due the next morning`() {
    assertEquals(
        LocalDateTime.of(2026, 8, 13, 13, 0),
        DigestSchedule.nextDelivery(Aggregation.DAILY, LocalDateTime.of(2026, 8, 12, 13, 0)))
  }

  @Test
  fun `weekly digest is due the next Monday morning`() {
    // 2026-08-12 is a Wednesday.
    assertEquals(
        LocalDateTime.of(2026, 8, 17, 13, 0),
        DigestSchedule.nextDelivery(Aggregation.WEEKLY, LocalDateTime.of(2026, 8, 12, 12, 0)))
    // Monday after the window rolls to the following Monday.
    assertEquals(
        LocalDateTime.of(2026, 8, 24, 13, 0),
        DigestSchedule.nextDelivery(Aggregation.WEEKLY, LocalDateTime.of(2026, 8, 17, 14, 0)))
  }

  @Test
  fun `windows follow Central time across daylight saving`() {
    // January: Central is UTC-6, so 8:00 AM Central = 14:00 UTC.
    assertEquals(
        LocalDateTime.of(2026, 1, 15, 14, 0),
        DigestSchedule.nextDelivery(Aggregation.DAILY, LocalDateTime.of(2026, 1, 15, 3, 0)))
  }

  @Test
  fun `immediate has no window`() {
    assertNull(DigestSchedule.nextDelivery(Aggregation.IMMEDIATE, LocalDateTime.now()))
  }

  @Test
  fun `start of Central day is midnight Central in UTC`() {
    // 03:00 UTC on Aug 12 is still Aug 11 in Central.
    assertEquals(
        LocalDateTime.of(2026, 8, 11, 5, 0),
        DigestSchedule.startOfCentralDay(LocalDateTime.of(2026, 8, 12, 3, 0)))
  }
}

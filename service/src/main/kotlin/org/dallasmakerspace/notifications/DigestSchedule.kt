package org.dallasmakerspace.notifications

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * When digest rows become due. Rows are stored with `next_attempt_at` set to the next delivery
 * window so the digest processor only picks up (and combines) rows whose window has arrived.
 * All inputs/outputs are UTC; windows are defined in Central Time.
 */
object DigestSchedule {
  private val UTC = ZoneId.of("UTC")
  private val CENTRAL = ZoneId.of("America/Chicago")
  private const val SEND_HOUR = 8
  private val SEND_TIME: LocalTime = LocalTime.of(SEND_HOUR, 0)
  private val WEEKLY_DAY = DayOfWeek.MONDAY

  /** Human-readable summary of the windows, for UI help text. */
  const val DESCRIPTION = "Daily digests go out at 8 AM; weekly digests on Mondays at 8 AM (Central)."

  /** Next delivery window (UTC) strictly after [nowUtc] for [aggregation]; null for IMMEDIATE. */
  fun nextDelivery(aggregation: Aggregation, nowUtc: LocalDateTime): LocalDateTime? {
    val now = nowUtc.atZone(UTC).withZoneSameInstant(CENTRAL)
    val next =
        when (aggregation) {
          Aggregation.IMMEDIATE -> return null
          Aggregation.DAILY -> {
            val today = now.with(SEND_TIME)
            if (today.isAfter(now)) today else today.plusDays(1)
          }
          Aggregation.WEEKLY -> {
            val thisWeek = now.with(TemporalAdjusters.nextOrSame(WEEKLY_DAY)).with(SEND_TIME)
            if (thisWeek.isAfter(now)) thisWeek else thisWeek.plusWeeks(1)
          }
        }
    return next.withZoneSameInstant(UTC).toLocalDateTime()
  }

  /** Start of the current Central-Time calendar day, in UTC (for per-day caps). */
  fun startOfCentralDay(nowUtc: LocalDateTime): LocalDateTime =
      nowUtc
          .atZone(UTC)
          .withZoneSameInstant(CENTRAL)
          .toLocalDate()
          .atStartOfDay(CENTRAL)
          .withZoneSameInstant(UTC)
          .toLocalDateTime()
}

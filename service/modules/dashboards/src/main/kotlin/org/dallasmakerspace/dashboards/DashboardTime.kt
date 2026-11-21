package org.dallasmakerspace.dashboards

import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/** The dashboard's editor times are Central; the database holds UTC. */
object DashboardTime {
  val UTC: ZoneId = ZoneId.of("UTC")
  val CHICAGO: ZoneId = ZoneId.of("America/Chicago")

  /** Parses an editor `yyyy-MM-ddTHH:mm` (Central) into UTC; blank means no bound. */
  fun chicagoToUtc(value: String?): LocalDateTime? {
    if (value.isNullOrBlank()) return null
    val local =
        try {
          LocalDateTime.parse(value.trim())
        } catch (e: DateTimeParseException) {
          throw DashboardException("Invalid date/time: $value")
        }
    return local.atZone(CHICAGO).withZoneSameInstant(UTC).toLocalDateTime()
  }

  fun utcToChicago(value: LocalDateTime?): String? =
      value?.atZone(UTC)?.withZoneSameInstant(CHICAGO)?.toLocalDateTime()?.toString()

  /**
   * The moment an event starts, as epoch milliseconds: the UTC value when the calendar gave one, else its Central
   * start text (`2026-12-02 18:30:00`, which is all the prerequisite-class query returns). Null if neither reads.
   */
  fun eventStartMs(utcMs: Long?, centralText: String?): Long? {
    if (utcMs != null) return utcMs
    val text = centralText?.trim().orEmpty().replace(' ', 'T')
    if (text.isEmpty()) return null
    return try {
      LocalDateTime.parse(text).atZone(CHICAGO).toInstant().toEpochMilli()
    } catch (e: DateTimeParseException) {
      null
    }
  }

  fun utcMillis(value: LocalDateTime): Long = value.atZone(UTC).toInstant().toEpochMilli()

  fun nowUtc(): LocalDateTime = LocalDateTime.now(UTC)

  /** Parses `HH:mm`; blank is null. */
  fun parseClock(value: String?): LocalTime? {
    if (value.isNullOrBlank()) return null
    return try {
      LocalTime.parse(value.trim())
    } catch (e: DateTimeParseException) {
      throw DashboardException("Invalid time: $value")
    }
  }
}

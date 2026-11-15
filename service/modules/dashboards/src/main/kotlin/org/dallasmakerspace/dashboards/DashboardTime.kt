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

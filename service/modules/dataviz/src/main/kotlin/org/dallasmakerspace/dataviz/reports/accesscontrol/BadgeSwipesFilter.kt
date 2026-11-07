package org.dallasmakerspace.dataviz.reports.accesscontrol

import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * What the badge swipes report shows: `doors` (repeatable) and `from`/`to` in Central time
 * (2026-11-05T17:00). Everything is validated here because the report's query is built as text.
 */
internal class BadgeSwipesFilter
private constructor(
    private val doors: List<String>,
    private val from: LocalDateTime?,
    private val to: LocalDateTime?,
) {
  fun whereClause(): String =
      buildList {
            add(if (from == null) "e.date >= DATE_SUB(NOW(), INTERVAL 72 HOUR)" else "e.date >= '${SQL_TIME.format(from)}'")
            if (to != null) add("e.date <= '${SQL_TIME.format(to)}'")
            if (doors.isNotEmpty()) add("s.name IN (${doors.joinToString(",") { "'$it'" }})")
          }
          .joinToString(" AND ")

  companion object {
    private const val MAX_DOORS = 20
    private const val MAX_WINDOW_DAYS = 31L
    private val CENTRAL = ZoneId.of("America/Chicago")
    private val DOOR = Regex("^[A-Za-z0-9 /_-]{1,40}$")
    private val SQL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** Throws [IllegalArgumentException] with a message for the report when a value is invalid. */
    fun parse(params: Map<String, List<String>>): BadgeSwipesFilter {
      val doors = params["doors"].orEmpty()
      require(doors.size <= MAX_DOORS && doors.all(DOOR::matches)) { "Invalid doors" }
      val from = params["from"]?.firstOrNull()?.let(::utc)
      val to = params["to"]?.firstOrNull()?.let(::utc)
      if (to != null) requireNotNull(from) { "to needs from" }
      if (from != null && to != null) {
        require(to.isAfter(from)) { "to must be after from" }
        require(Duration.between(from, to) <= Duration.ofDays(MAX_WINDOW_DAYS)) { "The window is too long" }
      }
      return BadgeSwipesFilter(doors, from, to)
    }

    private fun utc(text: String): LocalDateTime =
        try {
          LocalDateTime.parse(text).atZone(CENTRAL).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime()
        } catch (e: DateTimeParseException) {
          throw IllegalArgumentException("Times must look like 2026-11-05T17:00", e)
        }
  }
}

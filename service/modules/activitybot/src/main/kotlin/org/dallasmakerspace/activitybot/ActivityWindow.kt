package org.dallasmakerspace.activitybot

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/** A time range in Central time, as the tools take it and as members read it. */
class ActivityWindow private constructor(val from: Instant, val to: Instant) {
  private val multiDay = Duration.between(from, to) > Duration.ofHours(HOURS_PER_DAY)
  private val clock = if (multiDay) DAY_CLOCK else CLOCK

  fun clock(time: Instant): String = clock.format(time.atZone(CENTRAL))

  /** [time] with its day, for a moment that may fall outside the window. */
  fun stamp(time: Instant): String = DAY_CLOCK.format(time.atZone(CENTRAL))

  override fun toString(): String = "${stamp(from)} to ${stamp(to)} Central"

  companion object {
    val CENTRAL: ZoneId = ZoneId.of("America/Chicago")
    const val MAX_DAYS = 7L
    private const val HOURS_PER_DAY = 24L
    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
    private val DAY_CLOCK = DateTimeFormatter.ofPattern("EEE MMM d HH:mm", Locale.US)
    private val DATE_TIME = Regex("""\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}(:\d{2})?""")
    private val DATE = Regex("""\d{4}-\d{2}-\d{2}""")

    /**
     * Parses [from] and [to] as Central dates or date-times (`2026-11-05` or `2026-11-05T17:00`).
     * A date alone means the start of that day for [from] and its end for [to].
     */
    fun parse(from: String?, to: String?, now: Instant = Instant.now()): ActivityWindow {
      val start = moment(from, endOfDay = false) ?: throw bad("from")
      val end = moment(to, endOfDay = true) ?: throw bad("to")
      require(end.isAfter(start)) { "The end of the window must be after its start." }
      require(Duration.between(start, end) <= Duration.ofDays(MAX_DAYS)) {
        "The window can be at most $MAX_DAYS days; ask for a shorter one."
      }
      require(start.isBefore(now)) { "That window is in the future." }
      return ActivityWindow(start, minOf(end, now))
    }

    private fun bad(name: String) =
        IllegalArgumentException("$name must look like 2026-11-05 or 2026-11-05T17:00 (Central time).")

    private fun moment(text: String?, endOfDay: Boolean): Instant? {
      val value = text?.trim() ?: return null
      return try {
        when {
          DATE_TIME.matches(value) ->
              LocalDateTime.parse(value.replace(' ', 'T')).atZone(CENTRAL).toInstant()
          DATE.matches(value) -> {
            val day = LocalDate.parse(value)
            (if (endOfDay) day.plusDays(1) else day).atStartOfDay(CENTRAL).toInstant()
          }
          else -> null
        }
      } catch (e: DateTimeParseException) {
        null
      }
    }
  }
}

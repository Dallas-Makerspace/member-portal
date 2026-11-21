package org.dallasmakerspace.dashboards

import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the TVs / Displays page says about a TV, worked out from what the cast agent last reported. */
object DisplayStatus {
  private val CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

  data class Summary(val online: Boolean?, val showing: String, val dashboard: Boolean)

  /** [raw] is the agent's last report: our app's name, "idle", another app's name, or "offline". */
  fun summarize(raw: String?): Summary {
    val text = raw?.trim().orEmpty()
    return when {
      text.isEmpty() -> Summary(null, "Not seen yet", false)
      text.equals("offline", ignoreCase = true) -> Summary(false, "Offline", false)
      text.equals("idle", ignoreCase = true) -> Summary(true, "Idle (nothing is casting)", false)
      text.contains("dashboard", ignoreCase = true) -> Summary(true, "The committee dashboard", true)
      else -> Summary(true, "Showing $text", false)
    }
  }

  /** Whether [now] is inside the display hours (they may run overnight) and a short line saying so. */
  fun schedule(now: LocalTime, start: String?, end: String?): Pair<Boolean, String> {
    val from = clock(start)
    val to = clock(end)
    if (from == null || to == null) return true to "Always on"
    val minute = now.withSecond(0).withNano(0)
    val inside = if (from < to) minute >= from && minute < to else minute >= from || minute < to
    return inside to (if (inside) "On until ${CLOCK.format(to)}" else "Off until ${CLOCK.format(from)}")
  }

  private fun clock(value: String?): LocalTime? =
      value?.takeIf { it.isNotBlank() }?.let { runCatching { LocalTime.parse(it.trim()) }.getOrNull() }
}

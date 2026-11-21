package org.dallasmakerspace.dashboards

import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the TVs / Displays page says about a TV, worked out from what the cast agent last reported. */
object DisplayStatus {
  private val CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

  /** A found-but-not-set-up device counts as on the network if a sweep saw it in the last couple of hours. */
  private val FRESH: java.time.Duration = java.time.Duration.ofHours(2)

  /** The badge state of a TV set up for a committee. */
  fun state(enabled: Boolean, online: Boolean?): String =
      when {
        !enabled -> DisplayState.DISABLED
        online == true -> DisplayState.ONLINE
        online == false -> DisplayState.OFFLINE
        else -> DisplayState.UNSEEN
      }

  /** The badge state of a device only the network sweep knows ([lastSeen] and [now] are both UTC). */
  fun discoveredState(lastSeen: java.time.LocalDateTime, now: java.time.LocalDateTime): String =
      if (java.time.Duration.between(lastSeen, now) <= FRESH) DisplayState.ON_NETWORK else DisplayState.AWAY

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

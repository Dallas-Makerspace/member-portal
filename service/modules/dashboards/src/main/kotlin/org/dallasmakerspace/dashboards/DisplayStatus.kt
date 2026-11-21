package org.dallasmakerspace.dashboards

import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the TVs / Displays page says about a TV, worked out from what the cast agent last reported. */
object DisplayStatus {
  private val CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

  /** A found-but-not-set-up device counts as on the network if a sweep saw it in the last couple of hours. */
  private val FRESH: java.time.Duration = java.time.Duration.ofHours(2)

  /**
   * What a device nobody has set up is doing, in plain words, from the app it runs and what it plays:
   * "Spotify", "YouTube: Shop safety (paused)", "Idle (ambient screen)", "Nothing running".
   */
  fun describeDiscovered(appName: String?, statusText: String?, media: DiscoveredMedia?): String {
    val app = appName?.trim().orEmpty()
    if (app.isEmpty()) return "Nothing running"
    if (app.equals("Backdrop", ignoreCase = true)) return "Idle (ambient screen)"
    val what =
        listOfNotNull(media?.title?.trim()?.takeIf { it.isNotEmpty() }, media?.artist?.trim()?.takeIf { it.isNotEmpty() })
            .joinToString(" · ")
            .ifEmpty { statusText?.trim()?.takeIf { it.isNotEmpty() && !it.equals(app, ignoreCase = true) } }
    val paused = if (media?.state.equals("PAUSED", ignoreCase = true)) " (paused)" else ""
    return (if (what == null) app else "$app: $what") + paused
  }

  /** A web address worth linking to: http or https only, so a stored value can never be a script link. */
  fun safeLink(url: String?): String? =
      url?.trim()?.takeIf { it.length <= LINK_MAX && (it.startsWith("http://", true) || it.startsWith("https://", true)) }

  private const val LINK_MAX = 1024

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

package org.dallasmakerspace.agenttools.activity

import java.net.URLEncoder
import java.time.Duration
import java.time.Instant
import org.dallasmakerspace.models.Tool
import org.dallasmakerspace.models.agent.AgentSourceLink

private const val MAX_REPORT_DAYS = 90L
private const val PORTAL = "Member portal"

/** Links to the portal reports behind an answer, so the member can check the data. */
internal object ActivityLinks {
  fun swipes(door: String, window: ActivityWindow): AgentSourceLink =
      AgentSourceLink(
          "$door badge swipes",
          "/reports/member-visits/badge-swipes?doors=${encode(door)}" +
              "&from=${window.param(window.from)}&to=${window.param(window.to)}",
          PORTAL)

  /** Null when the window is older than the tool's page can reach back. */
  fun usage(tool: Tool, window: ActivityWindow, now: Instant = Instant.now()): AgentSourceLink? {
    val days = Duration.between(window.from, now).toDays() + 1
    if (days > MAX_REPORT_DAYS) return null
    return AgentSourceLink(
        "${tool.name} usage", "/interlock-tools?tool=${encode(tool.interlockTag)}&days=$days", PORTAL)
  }

  /** A line for the model, so it can point the member at the same reports. */
  fun line(links: List<AgentSourceLink>): String =
      if (links.isEmpty()) "" else "\nCheck the data: " + links.joinToString("; ") { "[${it.title}](${it.url})" }

  private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8)
}

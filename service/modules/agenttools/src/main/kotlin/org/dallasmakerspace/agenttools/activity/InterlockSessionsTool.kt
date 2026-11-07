@file:Suppress("MaxLineLength")

package org.dallasmakerspace.agenttools.activity

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject
import org.dallasmakerspace.models.Area
import org.dallasmakerspace.models.Areas
import org.dallasmakerspace.agent.ToolResult
import org.dallasmakerspace.models.Tool

private const val MAX_LINES = 60

/** Sessions on interlocked tools (who, when, how long, how each ended) in a window. */
@Singleton
class InterlockSessionsTool
@Inject
constructor(
    access: ActivityAccess,
    names: MemberNames,
    private val sessions: SessionSearch,
) : ActivityTool(access, names) {

  override val descriptor =
      ToolDescriptor(
          "get_interlock_sessions",
          "Sessions on interlocked tools in a window, oldest first: who badged in, when, for how " +
              "long, and how each ended (badged out, idle timeout, cabinet " +
              "locked), plus denied start attempts. Windows are at most 7 days.",
          requiredParameters =
              listOf(
                  ToolParameterDescriptor("from", "Start of the window, $TIME_PARAMS", ToolParameterType.String),
                  ToolParameterDescriptor("to", "End of the window, $TIME_PARAMS", ToolParameterType.String)),
          optionalParameters =
              listOf(
                  ToolParameterDescriptor("tool", "Tool name or interlock tag, e.g. Auto Lift 1; default every tool you can see", ToolParameterType.String),
                  ToolParameterDescriptor("member", "Only this member's sessions, by username", ToolParameterType.String)))

  override suspend fun execute(args: JsonObject, areas: List<Area>, window: ActivityWindow): ToolResult {
    val visible = areas.flatMap { it.tools }
    val wanted = args.string("tool")
    val tools =
        if (wanted == null) {
          visible
        } else {
          visible.filter { it.name.equals(wanted, true) || it.interlockTag.equals(wanted, true) }
              .ifEmpty {
                throw IllegalArgumentException(
                    "Unknown tool \"$wanted\". You can ask about: ${visible.joinToString { it.name }}.")
              }
        }
    val reports = tools.mapNotNull { ActivityLinks.usage(it, window) }
    val member = memberArg(args)
    val found =
        sessions.search(tools, window.from, window.to).filter {
          member == null || it.username.equals(member, ignoreCase = true)
        }
    if (found.isEmpty()) {
      return ToolResult("No sessions were recorded on ${label(tools)} from $window${member?.let { " for $it" } ?: ""}." + ActivityLinks.line(reports), reports)
    }
    val shown = found.take(MAX_LINES)
    val display = names.of(shown.mapNotNull { it.username }.toSet())
    val lines =
        shown.map { s ->
          val person = who(s.username, s.badgeTail, display)
          if (s.authorized) {
            val span = "${window.clock(s.start)}-${s.end?.let(window::clock) ?: "?"}"
            val length = s.end?.let { " (${minutes(Duration.between(s.start, it))})" } ?: ""
            "$span$length | ${s.tool.name} | $person | ${endedText(s.endedBy, s.end == null)}"
          } else {
            "${window.clock(s.start)} | ${s.tool.name} | $person | denied start"
          }
        }
    val more = if (found.size > shown.size) "\nShowing the first $MAX_LINES of ${found.size}; narrow the window for the rest." else ""
    return ToolResult("${found.size} session${if (found.size == 1) "" else "s"} on ${label(tools)}, $window:\n" + lines.joinToString("\n") + more)
  }

  private fun label(tools: List<Tool>) = tools.joinToString { it.name }

  private fun minutes(d: Duration): String {
    val total = d.toMinutes()
    return if (total >= MINUTES_PER_HOUR) "${total / MINUTES_PER_HOUR}h ${total % MINUTES_PER_HOUR}m" else "${total}m"
  }

  private companion object {
    const val MINUTES_PER_HOUR = 60
  }
}

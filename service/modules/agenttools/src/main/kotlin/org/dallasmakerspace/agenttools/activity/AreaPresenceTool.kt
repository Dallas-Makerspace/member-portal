package org.dallasmakerspace.agenttools.activity

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import org.dallasmakerspace.models.Area
import org.dallasmakerspace.models.Areas
import org.dallasmakerspace.agent.ToolResult
import org.dallasmakerspace.doorcontroller.DoorSwipe

private const val MAX_PEOPLE = 15
private const val MAX_SWIPES = 300
private const val MAX_TIMES = 3

/** Who was seen in an area during a window, from door entries and interlocked-tool sessions. */
@Singleton
class AreaPresenceTool
@Inject
constructor(
    access: ActivityAccess,
    names: MemberNames,
    private val swipes: SwipeSearch,
    private val sessions: SessionSearch,
) : ActivityTool(access, names) {

  override val descriptor =
      ToolDescriptor(
          "get_area_presence",
          "Who was seen in an area of the space during a window, strongest evidence first: " +
              "people who used an interlocked tool there, then people " +
              "whose badge opened an area door. Door readers record entries only, so this shows " +
              "who was likely there, not who stayed or who is responsible. Areas: " +
              Areas.ALL.joinToString { "${it.key} (${it.name})" } + ". Windows are at most 7 days.",
          requiredParameters =
              listOf(
                  ToolParameterDescriptor("area", "Area key, e.g. auto", ToolParameterType.String),
                  ToolParameterDescriptor("from", "Start of the window, $TIME_PARAMS", ToolParameterType.String),
                  ToolParameterDescriptor("to", "End of the window, $TIME_PARAMS", ToolParameterType.String)),
          optionalParameters = emptyList())

  override suspend fun execute(args: JsonObject, areas: List<Area>, window: ActivityWindow): ToolResult {
    val requested = args.string("area") ?: throw IllegalArgumentException("area is required.")
    val area =
        Areas.find(requested)?.takeIf { it in areas }
            ?: throw IllegalArgumentException(
                "Unknown area \"$requested\". You can ask about: ${areas.joinToString { it.key }}.")

    val (entries, uses, gap) =
        coroutineScope {
          val doors = async { swipes.search(area.doors, window.from, window.to, null, false, MAX_SWIPES + 1) }
          val tools = async { sessions.search(area.tools, window.from, window.to) }
          val freshness = async { doorLogGap(swipes, area.doors, window) }
          Triple(doors.await(), tools.await(), freshness.await())
        }
    val reports = area.doors.map { ActivityLinks.swipes(it, window) } + area.tools.mapNotNull { ActivityLinks.usage(it, window) }
    val shownSwipes = entries.take(MAX_SWIPES)
    val ranked = Presence.rank(uses, shownSwipes)
    if (ranked.isEmpty()) {
      return ToolResult(
          "No door entries or tool sessions were recorded in the ${area.name} from $window." +
              (gap?.let { "\n$it" } ?: "") +
              ActivityLinks.line(reports),
          reports)
    }
    val shown = ranked.take(MAX_PEOPLE)
    val display = names.of(shown.mapNotNull { it.username }.toSet())
    val lines =
        shown.mapIndexed { i, c ->
          "${i + 1}. ${who(c.username, c.badgeTail, display)} | ${evidence(c, window)}"
        }
    val notes = buildList {
      gap?.let { add(it) }
      if (ranked.size > shown.size) {
        add("${ranked.size - shown.size} more people had weaker evidence and are not shown.")
      }
      if (entries.size > MAX_SWIPES) add("The door log was cut at $MAX_SWIPES entries; narrow the window for the rest.")
      add(
          "Evidence only: door readers record entries (not exits), tailgating and other doors " +
              "are invisible, and being seen is not proof of who left a mess.")
    }
    return ToolResult(
        "${area.name}, $window: ${ranked.size} ${if (ranked.size == 1) "person" else "people"} seen.\n" +
            lines.joinToString("\n") + "\n" + notes.joinToString("\n") + ActivityLinks.line(reports),
        reports)
  }

  private fun evidence(c: Candidate, window: ActivityWindow): String =
      buildList {
            c.sessions.forEach { add(session(it, window)) }
            if (c.entries.isNotEmpty()) add(times("entered", c.entries, window))
            if (c.denied.isNotEmpty()) add(times("denied at the door", c.denied, window))
            c.deniedAtTools.forEach { add("denied starting ${it.tool.name} ${window.clock(it.start)}") }
          }
          .joinToString("; ")

  private fun session(s: ToolSession, window: ActivityWindow): String {
    val span = "${window.clock(s.start)}-${s.end?.let(window::clock) ?: "?"}"
    return "used ${s.tool.name} $span (${endedText(s.endedBy, s.end == null)})"
  }

  private fun times(verb: String, swipes: List<DoorSwipe>, window: ActivityWindow): String {
    val shown = swipes.take(MAX_TIMES).joinToString(", ") { "${it.door} ${window.clock(it.time)}" }
    val more = if (swipes.size > MAX_TIMES) " +${swipes.size - MAX_TIMES} more" else ""
    return "$verb $shown$more"
  }
}

internal fun endedText(endedBy: String?, open: Boolean): String =
    when (endedBy) {
      "IDLE_TIMEOUT" -> "ended by idle timeout"
      "MANUAL_BADGE_OUT" -> "badged out"
      "STARTUP_CURRENT" -> "ended on startup current"
      "LOCKED" -> "cabinet locked"
      else -> if (open) "no end logged" else "ended"
    }

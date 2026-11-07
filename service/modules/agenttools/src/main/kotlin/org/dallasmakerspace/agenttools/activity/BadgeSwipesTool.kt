@file:Suppress("MaxLineLength")

package org.dallasmakerspace.agenttools.activity

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject
import org.dallasmakerspace.models.Area
import org.dallasmakerspace.models.Areas
import org.dallasmakerspace.agent.ToolResult

private const val MAX_LINES = 80

/** Badge swipes at the doors you can see (granted and denied) in a window. */
@Singleton
class BadgeSwipesTool
@Inject
constructor(
    access: ActivityAccess,
    names: MemberNames,
    private val swipes: SwipeSearch,
) : ActivityTool(access, names) {

  override val descriptor =
      ToolDescriptor(
          "get_badge_swipes",
          "Badge swipes at the doors you can see in a window, oldest first, with whether each was " +
              "granted. Readers record entries only. Windows are at most 7 days. Look only as far " +
              "as the question needs: do not build a general history of one member.",
          requiredParameters =
              listOf(
                  ToolParameterDescriptor("from", "Start of the window, $TIME_PARAMS", ToolParameterType.String),
                  ToolParameterDescriptor("to", "End of the window, $TIME_PARAMS", ToolParameterType.String)),
          optionalParameters =
              listOf(
                  ToolParameterDescriptor("door", "Door name, e.g. Garage-In; default every door you can see", ToolParameterType.String),
                  ToolParameterDescriptor("member", "Only this member's swipes, by username", ToolParameterType.String),
                  ToolParameterDescriptor("deniedOnly", "Only swipes that were denied", ToolParameterType.Boolean)))

  override suspend fun execute(args: JsonObject, areas: List<Area>, window: ActivityWindow): ToolResult {
    val visible = areas.flatMap { it.doors }.distinct()
    val wanted = args.string("door")
    val doors =
        if (wanted == null) {
          visible
        } else {
          visible.filter { it.equals(wanted, ignoreCase = true) }
              .ifEmpty {
                throw IllegalArgumentException("Unknown door \"$wanted\". You can ask about: ${visible.joinToString()}.")
              }
        }
    val member = memberArg(args)
    val found =
        swipes.search(doors, window.from, window.to, member?.let { setOf(it) }, args.bool("deniedOnly") == true, MAX_LINES + 1)
    val label = doors.joinToString()
    val gap = doorLogGap(swipes, doors, window)?.let { "\n$it" } ?: ""
    val reports = doors.map { ActivityLinks.swipes(it, window) }
    if (found.isEmpty()) return ToolResult("No swipes were recorded at $label from $window${member?.let { " for $it" } ?: ""}.$gap" + ActivityLinks.line(reports), reports)
    val shown = found.take(MAX_LINES)
    val display = names.of(shown.mapNotNull { it.username }.toSet())
    val lines =
        shown.map {
          "${window.clock(it.time)} | ${it.door} | ${if (it.granted) "granted" else "denied"} | ${who(it.username, it.badgeTail, display)}"
        }
    val more = if (found.size > shown.size) "\nShowing the first $MAX_LINES; narrow the window for the rest." else ""
    return ToolResult("${shown.size} swipe${if (shown.size == 1) "" else "s"} at $label, $window:\n" + lines.joinToString("\n") + more + gap + ActivityLinks.line(reports), reports)
  }
}

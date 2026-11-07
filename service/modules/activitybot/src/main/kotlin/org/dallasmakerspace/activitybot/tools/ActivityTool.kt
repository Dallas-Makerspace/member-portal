package org.dallasmakerspace.activitybot.tools

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.dallasmakerspace.activitybot.ActivityAccess
import org.dallasmakerspace.activitybot.ActivityWindow
import org.dallasmakerspace.activitybot.Area
import org.dallasmakerspace.activitybot.MemberNames
import org.dallasmakerspace.activitybot.SwipeSearch
import org.dallasmakerspace.agent.AgentTool
import org.dallasmakerspace.agent.MemberAccess
import org.dallasmakerspace.agent.ToolContext
import org.dallasmakerspace.agent.ToolResult

internal fun JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null }

internal fun JsonObject.bool(name: String): Boolean? = this[name]?.jsonPrimitive?.booleanOrNull

private val USERNAME = Regex("^[a-zA-Z0-9_.-]{1,64}$")

/**
 * Shared checks for the activity tools: staff-only, limited to the areas the asker may see, with a
 * parsed Central-time window. Subclasses only build the answer.
 */
abstract class ActivityTool(
    private val access: ActivityAccess,
    protected val names: MemberNames,
) : AgentTool {
  override val requiredMemberAccess = MemberAccess.ANY_MEMBER

  override val touchesPersonalInfo = true

  override val maxResultChars = RESULT_CHARS

  protected abstract suspend fun execute(args: JsonObject, areas: List<Area>, window: ActivityWindow): ToolResult

  @Suppress("TooGenericExceptionCaught", "SwallowedException", "ReturnCount")
  final override suspend fun run(args: JsonObject, context: ToolContext): ToolResult {
    if (context.memberAccess != MemberAccess.ANY_MEMBER) {
      return ToolResult("This lookup is only available to staff assistants.", isError = true)
    }
    val areas = access.areasOf(context.username)
    if (areas.isEmpty()) return ToolResult("You can't look up activity in any area.", isError = true)
    return try {
      execute(args, areas, ActivityWindow.parse(args.string("from"), args.string("to")))
    } catch (e: CancellationException) {
      throw e
    } catch (e: IllegalArgumentException) {
      ToolResult(e.message ?: "Invalid request.", isError = true)
    } catch (e: Exception) {
      ToolResult("The activity data is unavailable right now.", isError = true)
    }
  }

  /** The `member` argument if present: a username, validated. */
  protected fun memberArg(args: JsonObject): String? =
      args.string("member")?.also { require(USERNAME.matches(it)) { "member must be a username." } }

  /** "jdoe (Jane Doe)", or the last four digits for a badge that isn't linked to a member. */
  protected fun who(username: String?, badgeTail: String?, display: Map<String, String>): String =
      when {
        username == null -> "unlinked badge ending ${badgeTail ?: "????"}"
        display[username] != null -> "$username (${display[username]})"
        else -> username
      }

  /**
   * A warning when the doors' controllers recorded nothing up to the end of [window], so a quiet
   * door log is not mistaken for an empty room. Null when the log reaches the end of the window.
   */
  protected suspend fun doorLogGap(swipes: SwipeSearch, doors: Collection<String>, window: ActivityWindow): String? {
    val latest = swipes.latestEvent(doors)
    return when {
      latest != null && latest >= window.to -> null
      latest == null -> "Door log gap: no events are recorded for these doors at all, so door entries are missing."
      else ->
          "Door log gap: the last event recorded by these doors' controllers was ${window.stamp(latest)} " +
              "Central, before this window ended, so door entries after that are missing."
    }
  }

  companion object {
    const val RESULT_CHARS = 6_000
    const val TIME_PARAMS =
        "Central time, as 2026-11-05 or 2026-11-05T17:00. A date alone means the whole day " +
            "(start of day for from, end of day for to)"
  }
}

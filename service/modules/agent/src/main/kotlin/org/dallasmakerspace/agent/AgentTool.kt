package org.dallasmakerspace.agent

import ai.koog.agents.core.tools.ToolDescriptor
import kotlinx.serialization.json.JsonObject
import org.dallasmakerspace.models.agent.AgentSourceLink

/** Whose member data a bot's tools may reach. Set on the bot; the runtime enforces it. */
enum class MemberAccess {
  /** Only the member asking the question. */
  SELF_ONLY,

  /** Any member, for staff assistants whose audience is already restricted. */
  ANY_MEMBER,
}

/** A tool a bot can call. Implementations must be read-only unless the bot says otherwise. */
interface AgentTool {
  val descriptor: ToolDescriptor

  /**
   * What the model is shown for a bot with [access]. A tool that can look up other members drops
   * its member parameter when [access] is [MemberAccess.SELF_ONLY].
   */
  fun descriptorFor(access: MemberAccess): ToolDescriptor = descriptor

  /** The least access a bot needs to carry this tool; a bot with less cannot be built. */
  val requiredMemberAccess: MemberAccess
    get() = MemberAccess.SELF_ONLY

  /** True when results can contain member data; running it makes the conversation private. */
  val touchesPersonalInfo: Boolean
    get() = false

  /** Results are cut to this length before the model sees them. */
  val maxResultChars: Int
    get() = DEFAULT_MAX_RESULT_CHARS

  suspend fun run(args: JsonObject, context: ToolContext): ToolResult

  companion object {
    const val DEFAULT_MAX_RESULT_CHARS = 8_000
  }
}

data class ToolContext(
    val username: String,
    val conversationId: String,
    val botKey: String,
    /** Set by the runtime from the bot; tools must not widen it. */
    val memberAccess: MemberAccess = MemberAccess.SELF_ONLY,
) {
  /**
   * The member whose data a tool may read: the asker unless the bot has [MemberAccess.ANY_MEMBER].
   * Null when [requested] names someone else and the bot may not look them up.
   */
  fun subjectFor(requested: String?): String? {
    val name = requested?.trim()?.takeIf { it.isNotEmpty() } ?: return username
    return if (name.equals(username, ignoreCase = true) || memberAccess == MemberAccess.ANY_MEMBER) name
    else null
  }
}

data class ToolResult(
    val output: String,
    val sources: List<AgentSourceLink> = emptyList(),
    val isError: Boolean = false,
    /** JSON `{id, version, kind, title}` when the tool saved an artifact; sent to the browser. */
    val artifactChange: String? = null,
)

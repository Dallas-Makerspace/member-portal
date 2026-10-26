package org.dallasmakerspace.agent

import ai.koog.agents.core.tools.ToolDescriptor
import kotlinx.serialization.json.JsonObject
import org.dallasmakerspace.models.agent.AgentSourceLink

/** A tool a bot can call. Implementations must be read-only unless the bot says otherwise. */
interface AgentTool {
  val descriptor: ToolDescriptor

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

data class ToolContext(val username: String, val conversationId: String, val botKey: String)

data class ToolResult(
    val output: String,
    val sources: List<AgentSourceLink> = emptyList(),
    val isError: Boolean = false,
)

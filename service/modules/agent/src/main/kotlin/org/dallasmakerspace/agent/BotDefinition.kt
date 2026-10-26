package org.dallasmakerspace.agent

import kotlinx.serialization.json.JsonElement
import org.dallasmakerspace.config.ConfigKey
import org.dallasmakerspace.config.FeatureFlagValue

/** Who may use a bot, on top of its feature flag. */
sealed interface BotAudience {
  data object AllMembers : BotAudience

  /** Members of the AD group named by [groupKey]'s current value. */
  data class AdGroup(val groupKey: ConfigKey<String>) : BotAudience

  /** Members of any AD group whose name ends with [suffix]. */
  data class AdGroupSuffix(val suffix: String) : BotAudience
}

/** A chat bot: its prompt, tools and who may use it. Feature modules contribute these. */
data class BotDefinition(
    val key: String,
    val displayName: String,
    val systemPrompt: String,
    val tools: List<AgentTool>,
    val featureFlag: ConfigKey<FeatureFlagValue>,
    val audience: BotAudience = BotAudience.AllMembers,
    val startsPrivate: Boolean = false,
    val maxToolCallsPerTurn: Int = DEFAULT_MAX_TOOL_CALLS,
    val starterPrompts: List<String> = emptyList(),
    /** Extra model request fields, e.g. a reasoning effort; null uses the model's default. */
    val modelRequestFields: Map<String, JsonElement>? = null,
) {
  companion object {
    const val DEFAULT_MAX_TOOL_CALLS = 8
  }
}

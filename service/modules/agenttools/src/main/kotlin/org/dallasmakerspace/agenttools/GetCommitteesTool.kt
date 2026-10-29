package org.dallasmakerspace.agenttools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.dallasmakerspace.agent.AgentTool
import org.dallasmakerspace.agent.ToolContext
import org.dallasmakerspace.agent.ToolResult
import org.dallasmakerspace.models.Committee
import org.dallasmakerspace.models.Committees
import org.dallasmakerspace.models.agent.AgentSourceLink

private const val MAX_COMMITTEES = 30
private const val DETAIL_LIMIT = 6
private const val FULL_DESCRIPTION_CHARS = 300
private const val SHORT_DESCRIPTION_CHARS = 100
private const val MAX_SOURCES = 6
private const val MAX_FILTER_CHARS = 60

/** Lists the active committees from static data: what they do, where their page is, which groups they own. */
@Singleton
class GetCommitteesTool @Inject constructor() : AgentTool {
  override val descriptor =
      ToolDescriptor(
          "get_committees",
          "List Dallas Makerspace committees: what each does, its homepage on Source, the name of its " +
              "chair group, its DMS Learn course categories and the group-name prefixes it owns. " +
              "Use it to find which committee runs an area. Filter by part of a committee name.",
          requiredParameters = emptyList(),
          optionalParameters =
              listOf(
                  ToolParameterDescriptor(
                      "name", "Part of a committee name, e.g. wood. Omit to list all.", ToolParameterType.String)))

  override suspend fun run(args: JsonObject, context: ToolContext): ToolResult {
    val filter = args["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    val matches =
        Committees.getActiveCommittees().filter { filter.isEmpty() || it.name.contains(filter, ignoreCase = true) }
    if (matches.isEmpty()) {
      return ToolResult("No active committee name contains \"${cleanText(filter, MAX_FILTER_CHARS)}\".")
    }
    val shown = matches.take(MAX_COMMITTEES)
    val detailed = shown.size <= DETAIL_LIMIT
    val text = shown.joinToString("\n\n") { describe(it, detailed) }
    val more =
        if (detailed) ""
        else "\n\n(Descriptions are shortened; pass a name to get one committee in full.)"
    return ToolResult(
        text + more,
        shown.filter { it.homepageUrl != null }.take(MAX_SOURCES).map {
          AgentSourceLink("${it.name} committee", it.homepageUrl!!, "Source")
        })
  }

  private fun describe(c: Committee, detailed: Boolean): String = buildString {
    append("Committee: ${c.name}")
    c.description?.let {
      append("\nAbout: ${cleanText(it, if (detailed) FULL_DESCRIPTION_CHARS else SHORT_DESCRIPTION_CHARS)}")
    }
    append("\nHomepage: ${c.homepageUrl ?: "none listed"}")
    append("\nChair group: ${c.chairGroupName ?: "none listed"}")
    append("\nDMS Learn categories: ${c.learnCategories.joinToString().ifEmpty { "none listed" }}")
    append("\nGroup name prefixes: ${c.groupPrefixes.joinToString().ifEmpty { "none listed" }}")
  }
}

package org.dallasmakerspace.coursebot.tools

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
import org.dallasmakerspace.models.agent.AgentSourceLink
import org.dallasmakerspace.moodle.MoodleCatalogService

private const val MAX_COURSES = 25
private const val SUMMARY_CHARS = 200

/** Lists the published DMS Learn courses so new material can fit what already exists. */
@Singleton
class ListLearnCoursesTool @Inject constructor(private val catalog: MoodleCatalogService) : AgentTool {
  override val descriptor =
      ToolDescriptor(
          "list_learn_courses",
          "List published DMS Learn courses (name, category, committee, short summary, link). " +
              "Filter by a word in the name, category or committee. Use it to avoid duplicating an " +
              "existing course and to match its style.",
          requiredParameters = emptyList(),
          optionalParameters =
              listOf(ToolParameterDescriptor("filter", "Word to look for, e.g. laser", ToolParameterType.String)))

  override suspend fun run(args: JsonObject, context: ToolContext): ToolResult {
    val filter = args["filter"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase().orEmpty()
    val matches =
        catalog.getCatalog().courses.filter { c ->
          filter.isEmpty() ||
              listOfNotNull(c.name, c.categoryName, c.committeeName).any { it.lowercase().contains(filter) }
        }
    if (matches.isEmpty()) return ToolResult("No published courses matched.")
    val shown = matches.take(MAX_COURSES)
    val lines =
        shown.joinToString("\n\n") { c ->
          "Title: ${c.name}\nCategory: ${c.categoryName}" +
              (c.committeeName?.let { "\nCommittee: $it" } ?: "") +
              "\nLink: ${c.url}\nSummary: ${c.summary.take(SUMMARY_CHARS).ifBlank { "(none)" }}"
        }
    val more = if (matches.size > shown.size) "\n\n(${matches.size - shown.size} more not shown; narrow the filter)" else ""
    return ToolResult(lines + more, shown.map { AgentSourceLink(it.name, it.url, "DMS Learn") })
  }
}

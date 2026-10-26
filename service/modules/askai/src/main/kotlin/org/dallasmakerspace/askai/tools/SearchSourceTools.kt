package org.dallasmakerspace.askai.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.dallasmakerspace.agent.AgentTool
import org.dallasmakerspace.agent.ToolContext
import org.dallasmakerspace.agent.ToolResult
import org.dallasmakerspace.askai.search.CalendarSearchSource
import org.dallasmakerspace.askai.search.DiscourseSearchSource
import org.dallasmakerspace.askai.search.MoodleCourseSearchSource
import org.dallasmakerspace.askai.search.SearchSource
import org.dallasmakerspace.models.SearchResult
import org.dallasmakerspace.models.agent.AgentSourceLink

private const val RESULT_LIMIT = 6

/** Wraps an existing [SearchSource] as a tool. */
abstract class SearchSourceTool(
    private val source: SearchSource,
    name: String,
    description: String,
    queryHint: String,
    override val touchesPersonalInfo: Boolean = false,
) : AgentTool {
  override val descriptor =
      ToolDescriptor(
          name,
          description,
          listOf(ToolParameterDescriptor("query", queryHint, ToolParameterType.String)))

  protected open fun header(): String = ""

  override suspend fun run(args: JsonObject, context: ToolContext): ToolResult {
    val query = args["query"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    if (query.isEmpty()) return ToolResult("Missing query.", isError = true)
    val results = source.search(query, RESULT_LIMIT)
    if (results.isEmpty()) return ToolResult(header() + "No results for \"$query\".")
    return ToolResult(
        header() + results.joinToString("\n\n") { it.asText() },
        results.map { AgentSourceLink(it.title, it.url, source.getName()) })
  }

  private fun SearchResult.asText() = "Title: $title\nLink: $url\nDetails: $snippet"
}

@Singleton
class SearchTalkTool @Inject constructor(source: DiscourseSearchSource) :
    SearchSourceTool(
        source,
        "search_talk",
        "Search Talk, the Dallas Makerspace community forum (talk.dallasmakerspace.org), for " +
            "member discussions from the last 18 months. Community opinions and tips, not official " +
            "policy, and possibly outdated.",
        "Short keyword phrase, 2-5 words, not a question")

@Singleton
class SearchCalendarTool @Inject constructor(source: CalendarSearchSource) :
    SearchSourceTool(
        source,
        "search_calendar",
        "Search the Dallas Makerspace calendar for classes, SIG meetups and events within 30 days " +
            "before and after today. Returns names, start times (Central) and organizers.",
        "Subject keywords such as laser or welding",
        touchesPersonalInfo = true) {
  override fun header() = "Today is ${LocalDate.now(CENTRAL)} (Central time).\n\n"

  private companion object {
    val CENTRAL: ZoneId = ZoneId.of("America/Chicago")
  }
}

@Singleton
class SearchOnlineCoursesTool @Inject constructor(source: MoodleCourseSearchSource) :
    SearchSourceTool(
        source,
        "search_online_courses",
        "Search DMS Learn, the Dallas Makerspace self-paced online course catalog.",
        "Subject keywords such as laser safety")

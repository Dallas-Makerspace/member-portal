package org.dallasmakerspace.askai.tools

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
import org.dallasmakerspace.askai.confluence.ContentTypeFilter
import org.dallasmakerspace.askai.confluence.IConfluenceApiClient
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.models.agent.AgentSourceLink

private const val SOURCE_NAME = "Source"
private const val SEARCH_LIMIT = 6
private const val EXCERPT_CHARS = 500
private const val PAGE_CHARS = 12_000
private val MEETING_NOTES = Regex("""(?i)(meeting|minutes)""")
private val YEAR = Regex("""\d{4}""")

private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

private fun absolute(baseUrl: String, path: String?): String? =
    path?.let { if (it.startsWith("http")) it else baseUrl + it }

/** Searches Source, the Dallas Makerspace documentation site (Confluence). */
@Singleton
class SearchSourceWikiTool
@Inject
constructor(
    private val confluence: IConfluenceApiClient,
    private val appConfig: AppConfig,
) : AgentTool {
  override val descriptor =
      ToolDescriptor(
          "search_source",
          "Search Source, the official Dallas Makerspace documentation site (source.dallasmakerspace.org): " +
              "policies, rules, procedures, onboarding, equipment and committee pages, and meeting " +
              "notes. Returns page titles, links, ids and excerpts.",
          listOf(
              ToolParameterDescriptor(
                  "query", "Short keyword phrase, 2-5 words, not a question", ToolParameterType.String)))

  override suspend fun run(args: JsonObject, context: ToolContext): ToolResult {
    val query = args.string("query")?.trim().orEmpty()
    if (query.isEmpty()) return ToolResult("Missing query.", isError = true)
    val baseUrl = appConfig.requireStringProperty("app.confluence.baseUrl")
    val hits = confluence.search(query, SEARCH_LIMIT, ContentTypeFilter.PAGES_ONLY).results
    val pages =
        hits.mapNotNull { hit ->
          val title = (hit.content?.title ?: hit.title).ifBlank { null }
          val url = absolute(baseUrl, hit.content?.links?.webui ?: hit.links?.webui ?: hit.url.ifBlank { null })
          val id = hit.content?.id ?: hit.id.ifBlank { null }
          if (title == null || url == null || id == null) null
          else WikiHit(title, url, id, HtmlText.plain(hit.excerpt).take(EXCERPT_CHARS))
        }
    if (pages.isEmpty()) return ToolResult("No pages found on Source for \"$query\".")
    val text =
        pages.joinToString("\n\n") { page ->
          val note =
              if (MEETING_NOTES.containsMatchIn(page.title) && YEAR.containsMatchIn(page.title)) {
                " (meeting notes - not official policy)"
              } else ""
          "Title: ${page.title}$note\nLink: ${page.url}\nId: ${page.id}\n" +
              "Excerpt: ${page.excerpt.ifBlank { "(none)" }}"
        }
    return ToolResult(text, pages.map { AgentSourceLink(it.title, it.url, SOURCE_NAME) })
  }

  private data class WikiHit(val title: String, val url: String, val id: String, val excerpt: String)
}

/** Reads one Source page in full. */
@Singleton
class ReadSourcePageTool
@Inject
constructor(
    private val confluence: IConfluenceApiClient,
    private val appConfig: AppConfig,
) : AgentTool {
  override val descriptor =
      ToolDescriptor(
          "read_source_page",
          "Read the full text of one Source page, using an id from search_source. Use it " +
              "when an excerpt is not enough to answer.",
          listOf(ToolParameterDescriptor("id", "Page id from search_source", ToolParameterType.String)))

  override val maxResultChars = PAGE_CHARS

  override suspend fun run(args: JsonObject, context: ToolContext): ToolResult {
    val id = args.string("id")?.trim().orEmpty()
    if (id.isEmpty() || !id.all { it.isDigit() }) return ToolResult("Invalid page id.", isError = true)
    val page = confluence.getContent(id)
    val text = HtmlText.plain(page.body?.view?.value ?: page.body?.storage?.value.orEmpty())
    val url = absolute(appConfig.requireStringProperty("app.confluence.baseUrl"), page.links?.webui)
    val link = url?.let { listOf(AgentSourceLink(page.title, it, SOURCE_NAME)) }.orEmpty()
    return ToolResult(
        "Title: ${page.title}\n" +
            (url?.let { "Link: $it\n" } ?: "") +
            "\n" +
            text.ifBlank { "(This page has no text.)" },
        link)
  }
}

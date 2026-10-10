package org.dallasmakerspace.askai.search

import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.dallasmakerspace.askai.confluence.ConfluenceSearchResult
import org.dallasmakerspace.askai.confluence.ContentTypeFilter
import org.dallasmakerspace.askai.confluence.IConfluenceApiClient
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.models.SearchResult

/**
 * Search source implementation for Confluence wiki. Searches the DMS Confluence wiki for relevant
 * pages and documentation.
 *
 * Runs two parallel queries: one for pages (extractable content) and one for attachments (PDFs).
 * This ensures we get both types of content instead of attachments dominating the results.
 *
 * For pages with empty excerpts, fetches the full page content to extract a snippet.
 */
class ConfluenceSearchSource
@Inject
constructor(
    private val confluenceClient: IConfluenceApiClient,
    private val appConfig: AppConfig,
    loggerFactory: LoggerFactory,
) : SearchSource {
  private val log = loggerFactory.create(javaClass)

  private val baseUrl: String by lazy { appConfig.requireStringProperty("app.confluence.baseUrl") }

  override fun getName(): String = "Source (Confluence)"

  override fun getPriority(): Int = 1 // Official documentation has the highest priority

  override suspend fun search(query: String, limit: Int): List<SearchResult> {
    log.debug("[ConfluenceSearch] Searching for: '$query' (limit=$limit)")

    return try {
      // Run two parallel queries: pages and attachments
      val (pageResults, attachmentResults) =
          coroutineScope {
            val pagesDeferred = async {
              try {
                confluenceClient.search(query, limit, ContentTypeFilter.PAGES_ONLY)
              } catch (e: Exception) {
                log.error("[ConfluenceSearch] Pages query failed: ${e.message}")
                null
              }
            }

            val attachmentsDeferred = async {
              try {
                confluenceClient.search(query, limit, ContentTypeFilter.ATTACHMENTS_ONLY)
              } catch (e: Exception) {
                log.error("[ConfluenceSearch] Attachments query failed: ${e.message}")
                null
              }
            }

            Pair(pagesDeferred.await(), attachmentsDeferred.await())
          }

      log.debug(
          "[ConfluenceSearch] Found ${pageResults?.results?.size ?: 0} pages, ${attachmentResults?.results?.size ?: 0} attachments")

      // Process results, fetching content for pages with empty excerpts
      val processedResults = coroutineScope {
        val pageSearchResults =
            (pageResults?.results ?: emptyList()).mapIndexed { idx, result ->
              async { processPageResult(idx, result) }
            }

        val attachmentSearchResults =
            (attachmentResults?.results ?: emptyList()).mapIndexed { idx, result ->
              // Attachments don't need content fetching, process synchronously
              async { processAttachmentResult(idx, result) }
            }

        (pageSearchResults + attachmentSearchResults).awaitAll().filterNotNull()
      }

      log.debug("[ConfluenceSearch] Returning ${processedResults.size} results")
      processedResults
    } catch (e: Exception) {
      log.error("[ConfluenceSearch] Failed to search for: '$query'", e)
      log.error(
          "[ConfluenceSearch] Exception type: ${e.javaClass.simpleName}, message: ${e.message}")
      emptyList()
    }
  }

  /** Process a page result, fetching full content if excerpt is empty. */
  private suspend fun processPageResult(idx: Int, result: ConfluenceSearchResult): SearchResult? {
    val title = result.content?.title?.ifBlank { null } ?: result.title.ifBlank { null }
    val webUrl = result.content?.links?.webui ?: result.links?.webui ?: result.url.ifBlank { null }
    val contentId = result.content?.id ?: result.id.ifBlank { null }

    if (title.isNullOrBlank()) {
      log.warn("[ConfluenceSearch] Page result $idx has no title, skipping")
      return null
    }

    if (webUrl.isNullOrBlank()) {
      log.warn("[ConfluenceSearch] Page result $idx has no URL, skipping")
      return null
    }

    val fullUrl = buildFullUrl(webUrl)

    // If excerpt is empty and we have a content ID, fetch the full page content
    val snippet =
        if (result.excerpt.isNotBlank()) {
          cleanSnippet(result.excerpt)
        } else if (!contentId.isNullOrBlank()) {
          log.debug("[ConfluenceSearch] Fetching content for page '$title' (id=$contentId)")
          fetchContentSnippet(contentId)
        } else {
          log.warn("[ConfluenceSearch] Page '$title' has no excerpt and no content ID")
          "[No content available]"
        }

    log.debug("[ConfluenceSearch] Page: '$title', snippet length: ${snippet.length}")

    val category =
        if (isMeetingNote(title)) SearchResult.MEETING_NOTES else SearchResult.OFFICIAL_DOCS

    return SearchResult(
        title = title,
        snippet = snippet,
        url = fullUrl,
        source = getName(),
        relevanceScore = null,
        hasExtractableContent = true,
        sourceCategory = category,
    )
  }

  /** Process an attachment result (PDFs, etc.). */
  private fun processAttachmentResult(idx: Int, result: ConfluenceSearchResult): SearchResult? {
    val title = result.content?.title?.ifBlank { null } ?: result.title.ifBlank { null }
    val webUrl = result.content?.links?.webui ?: result.links?.webui ?: result.url.ifBlank { null }

    if (title.isNullOrBlank()) {
      log.warn("[ConfluenceSearch] Attachment result $idx has no title, skipping")
      return null
    }

    if (webUrl.isNullOrBlank()) {
      log.warn("[ConfluenceSearch] Attachment result $idx has no URL, skipping")
      return null
    }

    val fullUrl = buildFullUrl(webUrl)
    val snippet =
        when {
          title.endsWith(".pdf", ignoreCase = true) -> "[PDF Document]"
          else -> "[Attachment]"
        }

    log.debug("[ConfluenceSearch] Attachment: '$title'")

    return SearchResult(
        title = title,
        snippet = snippet,
        url = fullUrl,
        source = getName(),
        relevanceScore = null,
        hasExtractableContent = false,
    )
  }

  /** Fetch full page content and extract a snippet from it. */
  private suspend fun fetchContentSnippet(contentId: String): String {
    return try {
      val content = confluenceClient.getContent(contentId)
      val bodyHtml = content.body?.view?.value ?: content.body?.storage?.value ?: ""

      if (bodyHtml.isBlank()) {
        log.warn("[ConfluenceSearch] Content $contentId has empty body")
        return "[No content available]"
      }

      // Extract text from HTML and create a snippet
      val textContent = extractTextFromHtml(bodyHtml)
      if (textContent.isBlank()) {
        "[No text content available]"
      } else {
        textContent.take(1500) // Larger snippet since we fetched full content
      }
    } catch (e: Exception) {
      log.error("[ConfluenceSearch] Failed to fetch content $contentId: ${e.message}")
      "[Failed to load content]"
    }
  }

  /** Extract plain text from HTML content. */
  private fun extractTextFromHtml(html: String): String {
    return html
        .replace(
            Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL),
            "",
        ) // Remove scripts
        .replace(Regex("<style[^>]*>.*?</style>", RegexOption.DOT_MATCHES_ALL), "") // Remove styles
        .replace(Regex("<[^>]+>"), " ") // Remove HTML tags
        .replace(Regex("&nbsp;"), " ")
        .replace(Regex("&amp;"), "&")
        .replace(Regex("&lt;"), "<")
        .replace(Regex("&gt;"), ">")
        .replace(Regex("&quot;"), "\"")
        .replace(Regex("&#\\d+;"), "") // Remove numeric entities
        .replace(Regex("\\s+"), " ") // Normalize whitespace
        .trim()
  }

  private fun buildFullUrl(path: String): String {
    return if (path.startsWith("http")) {
      path
    } else {
      "$baseUrl$path"
    }
  }

  private fun cleanSnippet(excerpt: String): String {
    // Remove HTML tags and clean up the excerpt
    return excerpt
        .replace(Regex("<[^>]+>"), "") // Remove HTML tags
        .replace(Regex("@@@hl@@@"), "") // Remove Confluence highlight markers
        .replace(Regex("@@@endhl@@@"), "")
        .replace(Regex("\\s+"), " ") // Normalize whitespace
        .trim()
        .take(600) // Limit snippet length
  }

  private fun isMeetingNote(title: String): Boolean {
    return Regex("""(?i)meeting[-\s]\d{4}|minutes[-\s]\d{4}|(?i)\d{4}[-\s]meeting""")
        .containsMatchIn(title) ||
        Regex("""(?i)^.*(meeting|minutes).*$""").matches(title) &&
            Regex("""\d{4}""").containsMatchIn(title)
  }
}

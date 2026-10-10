package org.dallasmakerspace.askai.search

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.discourse.IDiscourseApiClient
import org.dallasmakerspace.models.SearchResult

private const val DISCOURSE_BASE_URL = "https://talk.dallasmakerspace.org"
private const val SEARCH_MONTHS_BACK = 18L

/**
 * Search source implementation for Discourse forum. Searches the DMS Talk forum for relevant topics
 * and posts. Results are limited to the past 6 months to prioritize recent discussions.
 */
class DiscourseSearchSource
@Inject
constructor(private val discourseClient: IDiscourseApiClient, loggerFactory: LoggerFactory) :
    SearchSource {
  private val log = loggerFactory.create(javaClass)

  override fun getName(): String = "Talk"

  override fun getPriority(): Int = 2 // Community discussions after official docs

  override suspend fun search(query: String, limit: Int): List<SearchResult> {
    // Add date filter to limit results to past 6 months
    val afterDate = LocalDate.now().minusMonths(SEARCH_MONTHS_BACK)
    val dateFilter = "after:${afterDate.format(DateTimeFormatter.ISO_LOCAL_DATE)}"
    val queryWithDateFilter = "$query $dateFilter"

    log.debug("Searching Discourse for: $queryWithDateFilter")

    return try {
      val response = discourseClient.searchTopics(queryWithDateFilter)
      val results = mutableListOf<SearchResult>()

      // Convert topics to search results
      response.topics.take(limit).forEach { topic ->
        // Find matching post blurb if available
        val matchingPost = response.posts.find { it.topicId == topic.id }
        val snippet = matchingPost?.blurb ?: "Discussion topic on ${topic.title}"

        results.add(
            SearchResult(
                title = topic.title,
                snippet = cleanSnippet(snippet),
                url = "$DISCOURSE_BASE_URL/t/${topic.slug}/${topic.id}",
                source = getName(),
                relevanceScore = null,
                sourceCategory = SearchResult.TALK_FORUM,
            ))
      }

      log.debug("Found ${results.size} Discourse results")
      results
    } catch (e: Exception) {
      log.error("Failed to search Discourse for: $query", e)
      emptyList()
    }
  }

  private fun cleanSnippet(snippet: String): String {
    // Remove HTML tags and clean up the snippet
    return snippet
        .replace(Regex("<[^>]+>"), "") // Remove HTML tags
        .replace(Regex("\\s+"), " ") // Normalize whitespace
        .trim()
        .take(300) // Limit snippet length
  }
}

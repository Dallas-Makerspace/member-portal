package org.dallasmakerspace.askai.search

import org.dallasmakerspace.models.SearchResult

/**
 * Interface for a search source that can be queried for relevant content. Implementations should
 * search their respective data sources (Discourse, Confluence, etc.) and return normalized search
 * results.
 */
interface SearchSource {

  /**
   * Returns the display name of this search source (e.g., "Wiki", "Talk"). Used for identifying the
   * source of search results in the UI.
   */
  fun getName(): String

  /**
   * Returns the priority of this search source. Lower values = higher priority. Sources with higher
   * priority (lower values) will have their results shown first.
   *
   * Default priority levels:
   * - 1: Official documentation (Confluence wiki)
   * - 2: Community discussions (Discourse)
   * - 3: Other sources
   */
  fun getPriority(): Int = 2

  /**
   * Search for content matching the query.
   *
   * @param query The search query string
   * @param limit Maximum number of results to return
   * @return List of search results from this source
   */
  suspend fun search(query: String, limit: Int = 10): List<SearchResult>
}

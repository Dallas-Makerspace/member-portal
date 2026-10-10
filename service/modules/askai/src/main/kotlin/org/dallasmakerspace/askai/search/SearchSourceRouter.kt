package org.dallasmakerspace.askai.search

import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.models.SearchResult

/**
 * Router class for search sources. Routes search queries to all registered search sources and
 * aggregates results. Results are sorted by source priority (lower priority value = higher
 * priority).
 *
 * New search sources can be added by:
 * 1. Creating a class implementing SearchSource
 * 2. Adding it to the set in AskAiModule using @ElementsIntoSet
 */
class SearchSourceRouter
@Inject
constructor(
    searchSourceSet: Set<@JvmSuppressWildcards SearchSource>,
    loggerFactory: LoggerFactory,
) {
  private val log = loggerFactory.create(javaClass)
  private val sourceMap: Map<String, SearchSource> = searchSourceSet.associateBy { it.getName() }
  private val sourcePriorities: Map<String, Int> =
      searchSourceSet.associate { it.getName() to it.getPriority() }

  init {
    val sourceInfo =
        sourceMap.entries.joinToString(", ") { (name, source) ->
          "$name (priority=${source.getPriority()})"
        }
    log.info("Registered search sources: $sourceInfo")
  }

  /**
   * Search a specific source by name.
   *
   * @param sourceName The name of the search source (e.g., "discourse", "confluence")
   * @param query The search query
   * @param limit Maximum results per source
   * @return List of search results from the specified source, or empty if source not found
   */
  suspend fun search(sourceName: String, query: String, limit: Int = 10): List<SearchResult> {
    val source = sourceMap[sourceName]
    if (source == null) {
      log.warn("No search source found for: $sourceName")
      return emptyList()
    }
    return try {
      source.search(query, limit)
    } catch (e: Exception) {
      log.error("Error searching $sourceName for '$query'", e)
      emptyList()
    }
  }

  /**
   * Search all registered sources in parallel. Results are sorted by source priority (lower
   * priority value = shown first).
   *
   * @param query The search query
   * @param limitPerSource Maximum results per source
   * @return Aggregated list of search results from all sources, sorted by priority
   */
  suspend fun searchAll(query: String, limitPerSource: Int = 10): List<SearchResult> =
      coroutineScope {
        log.debug("Searching all sources for: $query")

        val results =
            sourceMap.values
                .map { source ->
                  async {
                    try {
                      source.search(query, limitPerSource)
                    } catch (e: Exception) {
                      log.error("Error searching ${source.getName()} for '$query'", e)
                      emptyList()
                    }
                  }
                }
                .awaitAll()
                .flatten()
                .sortedBy { sourcePriorities[it.source] ?: Int.MAX_VALUE }

        log.debug("Found ${results.size} total results across ${sourceMap.size} sources")
        results
      }

  /** Get the list of registered search source names. */
  fun getRegisteredSources(): List<String> = sourceMap.keys.toList()
}

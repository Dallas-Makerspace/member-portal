package org.dallasmakerspace.askai.search

import javax.inject.Inject
import javax.inject.Singleton
import org.dallasmakerspace.config.ConfigOverrideService
import org.dallasmakerspace.config.ConfigRegistry
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.models.SearchResult
import org.dallasmakerspace.moodle.MoodleCatalogService

private const val NAME_MATCH_WEIGHT = 3

/** DMS Learn online courses from the cached catalog; no per-member data. */
@Singleton
class MoodleCourseSearchSource
@Inject
constructor(
    private val catalogService: MoodleCatalogService,
    private val configOverrideService: ConfigOverrideService,
    loggerFactory: LoggerFactory,
) : SearchSource {
  private val log = loggerFactory.create(javaClass)

  override fun getName(): String = "DMS Learn"

  override fun getPriority(): Int = 2

  override suspend fun search(query: String, limit: Int): List<SearchResult> {
    // Ask AI has no per-member context here, so only the global switch counts (no pilots).
    if (!configOverrideService.get(ConfigRegistry.MOODLE_ENABLED).enabled) return emptyList()
    val terms = RelevanceScorer.extractQueryTerms(listOf(query))
    if (terms.isEmpty()) return emptyList()

    val matches =
        catalogService
            .getCatalog()
            .courses
            .filter { it.selfEnrol }
            .map { course ->
              val name = course.name.lowercase()
              val context =
                  "${course.summary} ${course.categoryName} ${course.committeeName.orEmpty()}"
                      .lowercase()
              course to terms.sumOf { term ->
                when {
                  term in name -> NAME_MATCH_WEIGHT
                  term in context -> 1
                  else -> 0
                }
              }
            }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    log.info("MoodleCourseSearch: terms=$terms matches=${matches.size}")

    return matches.map { course ->
      SearchResult(
          title = "${course.name} (DMS Learn online course)",
          snippet =
              buildString {
                append("Self-paced online course on DMS Learn")
                course.committeeName?.let { append(" from the $it committee") }
                append(". ")
                append(course.summary)
              },
          url = course.url,
          source = getName(),
      )
    }
  }
}

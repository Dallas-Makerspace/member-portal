package org.dallasmakerspace.moodle

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.models.Committee
import org.dallasmakerspace.models.Committees

private const val SUMMARY_MAX_LENGTH = 280
private const val AD_GROUP_FIELD = "ad_group"

/** Cached DMS Learn course catalog, with each course mapped to its committee. */
@Singleton
class MoodleCatalogService
@Inject
constructor(
    private val moodleApiClient: IMoodleApiClient,
    loggerFactory: LoggerFactory,
) {
  private val log = loggerFactory.create(javaClass)

  private val snapshot =
      RefreshingSnapshot(
          name = "Moodle course catalog",
          ttl = 1.hours,
          scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
          log = log,
      ) {
        loadCatalog()
      }

  val learnUrl: String
    get() = moodleApiClient.baseUrl

  /** All courses, hidden ones included (stats need them); empty if Moodle is unreachable. */
  suspend fun getAllCourses(): List<LearnCourse> = snapshot.get() ?: emptyList()

  suspend fun getCatalog(committee: Committee? = null): LearnCatalog =
      LearnCatalog(
          learnUrl = learnUrl,
          courses =
              getAllCourses().filter {
                it.visible && (committee == null || it.committeeSlug == committee.slug)
              },
      )

  private suspend fun loadCatalog(): List<LearnCourse> = coroutineScope {
    val categoriesDeferred = async { moodleApiClient.getCategories() }
    val courses = moodleApiClient.getCourses()
    val authorIds = courses.mapNotNull { it.contacts.firstOrNull()?.id }
    val usernames =
        runCatching { moodleApiClient.getUsernames(authorIds) }
            .onFailure { log.warn("Failed to resolve DMS Learn course author usernames", it) }
            .getOrDefault(emptyMap())
    buildCourses(categoriesDeferred.await(), courses, learnUrl, usernames)
  }

  companion object {
    internal fun buildCourses(
        categories: List<MoodleCategory>,
        courses: List<MoodleCourse>,
        learnUrl: String,
        usernamesById: Map<Int, String> = emptyMap(),
    ): List<LearnCourse> {
      val categoriesById = categories.associateBy { it.id }
      return courses
          .sortedBy { it.sortorder }
          .map { course ->
            val category = categoriesById[course.categoryid]
            val topLevel = category?.let { topLevelOf(it, categoriesById) }
            val topLevelName = MoodleText.plainText(topLevel?.name)
            val committee =
                topLevel?.let { top ->
                  Committees.ALL.firstOrNull {
                    it.matchesLearnCategory(top.idnumber, topLevelName) ||
                        it.name.equals(topLevelName, ignoreCase = true)
                  }
                }
            val author = course.contacts.firstOrNull()
            LearnCourse(
                id = course.id,
                shortname = MoodleText.plainText(course.shortname),
                name = MoodleText.plainText(course.displayname ?: course.fullname),
                summary = MoodleText.plainText(course.summary).let(::truncate),
                url = "$learnUrl/course/view.php?id=${course.id}",
                visible =
                    course.visible == 1 &&
                        (category?.visible ?: 1) == 1 &&
                        (topLevel?.visible ?: 1) == 1,
                categoryName = topLevelName,
                committeeSlug = committee?.slug,
                committeeName = committee?.name,
                authorName = author?.fullname?.let(MoodleText::plainText)?.takeIf { it.isNotEmpty() },
                authorUsername = author?.let { usernamesById[it.id] },
                selfEnrol = "self" in course.enrollmentmethods,
                adGroup =
                    course.customfields
                        .firstOrNull { it.shortname == AD_GROUP_FIELD }
                        ?.let { MoodleText.plainText(it.valueraw ?: it.value) }
                        ?.takeIf { it.isNotEmpty() },
            )
          }
    }

    private fun topLevelOf(
        category: MoodleCategory,
        categoriesById: Map<Int, MoodleCategory>,
    ): MoodleCategory {
      val rootId = category.path.split('/').firstOrNull { it.isNotBlank() }?.toIntOrNull()
      return rootId?.let { categoriesById[it] } ?: category
    }

    private fun truncate(text: String): String =
        if (text.length <= SUMMARY_MAX_LENGTH) text
        else text.take(SUMMARY_MAX_LENGTH).substringBeforeLast(' ').trimEnd(',', '.', ';') + "…"
  }
}

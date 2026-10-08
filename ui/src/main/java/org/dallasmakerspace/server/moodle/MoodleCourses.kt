package org.dallasmakerspace.server.moodle

import org.dallasmakerspace.models.Committees

const val MOODLE_FEATURE_FLAG = "moodle.enabled"

data class MoodleCourseView(
    val id: Int,
    val name: String,
    val summary: String,
    val url: String,
    val categoryName: String,
    val committeeSlug: String?,
    val committeeName: String?,
    val authorName: String? = null,
    val authorUsername: String? = null,
    val enrolled: Boolean = false,
    val completed: Boolean = false,
    val progress: Int? = null,
) {
  val inProgress: Boolean
    get() = enrolled && !completed

  fun toJson(): Map<String, Any?> =
      mapOf(
          "id" to id,
          "name" to name,
          "summary" to summary,
          "url" to url,
          "categoryName" to categoryName,
          "committeeName" to committeeName,
          "authorName" to authorName,
          "completed" to completed,
          "inProgress" to inProgress,
          "progress" to progress,
      )
}

object MoodleCourses {
  fun learnUrl(catalog: Map<String, Any?>?): String? = catalog?.get("learnUrl") as? String

  /** Visible catalog courses with the member's own status merged in; catalog order is kept. */
  fun parse(
      catalog: Map<String, Any?>?,
      memberCourses: List<Map<String, Any?>> = emptyList(),
  ): List<MoodleCourseView> {
    val statusById =
        memberCourses.mapNotNull { m -> (m["courseId"] as? Number)?.toInt()?.let { it to m } }.toMap()
    return (catalog?.get("courses") as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().mapNotNull {
        c ->
      val id = (c["id"] as? Number)?.toInt() ?: return@mapNotNull null
      val status = statusById[id]
      MoodleCourseView(
          id = id,
          name = c["name"] as? String ?: return@mapNotNull null,
          summary = c["summary"] as? String ?: "",
          url = c["url"] as? String ?: return@mapNotNull null,
          categoryName = c["categoryName"] as? String ?: "",
          committeeSlug = c["committeeSlug"] as? String,
          committeeName = c["committeeName"] as? String,
          authorName = c["authorName"] as? String,
          authorUsername = c["authorUsername"] as? String,
          enrolled = status != null,
          completed = status?.get("completed") == true,
          progress = (status?.get("progress") as? Number)?.toInt(),
      )
    }
  }

  /**
   * Home-page picks: courses already started first, then ones from committees whose AD groups the
   * member holds, then the rest; at most one per committee unless there aren't enough committees.
   * Completed courses are never suggested.
   */
  fun suggest(
      courses: List<MoodleCourseView>,
      memberGroups: Set<String>,
      limit: Int = 4,
  ): List<MoodleCourseView> {
    val groups = memberGroups.map { it.trimStart('/').lowercase() }
    val committeeSlugs =
        Committees.ALL.filter { c ->
              c.groupPrefixes.any { prefix -> groups.any { it.startsWith(prefix.lowercase()) } }
            }
            .map { it.slug }
            .toSet()
    val ranked =
        courses
            .filterNot { it.completed }
            .sortedWith(
                compareBy<MoodleCourseView> {
                      when {
                        it.inProgress -> 0
                        it.committeeSlug in committeeSlugs -> 1
                        else -> 2
                      }
                    }
                    .thenByDescending { it.progress ?: 0 })
    val seenCommittees = mutableSetOf<String>()
    val onePerCommittee = ranked.filter { seenCommittees.add(it.committeeSlug ?: it.categoryName) }
    return (onePerCommittee + (ranked - onePerCommittee.toSet())).take(limit)
  }
}

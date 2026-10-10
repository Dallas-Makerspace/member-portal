package org.dallasmakerspace.moodle

import java.time.LocalDate
import java.time.YearMonth
import org.dallasmakerspace.models.Committee
import org.dallasmakerspace.models.Committees

data class CourseStats(
    val course: LearnCourse?,
    val courseKey: String,
    val completed: Int,
    val completionsLastYear: Int,
    val lastCompleted: LocalDate?,
)

data class MonthStats(val month: YearMonth, val completions: Int, val newLearners: Int)

data class CommitteeStats(
    val committee: Committee,
    val courses: Int,
    val completionsLastYear: Int,
    val learnersLastYear: Int,
)

/** Pure aggregations over [EnrolmentRecord]s; "last year" means the 12 months up to [today]. */
object LearnStatsCalculator {

  fun courseStats(
      records: List<EnrolmentRecord>,
      courses: List<LearnCourse>,
      committee: Committee?,
      today: LocalDate,
  ): List<CourseStats> {
    val coursesByKey = courses.associateBy { MoodleText.normalizeKey(it.shortname) }
    val yearAgo = today.minusYears(1)
    val byCourse = records.groupBy { it.courseKey }
    val keys =
        (byCourse.keys + courses.filter { it.visible }.map { MoodleText.normalizeKey(it.shortname) })
            .filter { committee == null || coursesByKey[it]?.committeeSlug == committee.slug }
    return keys
        .map { key ->
          val completions = byCourse[key].orEmpty().mapNotNull { it.completedOn }
          CourseStats(
              course = coursesByKey[key],
              courseKey = key,
              completed = completions.size,
              completionsLastYear = completions.count { it.isAfter(yearAgo) },
              lastCompleted = completions.maxOrNull(),
          )
        }
        .filter { it.completed > 0 || it.course?.visible == true }
        .sortedWith(compareByDescending<CourseStats> { it.completionsLastYear }.thenByDescending { it.completed })
  }

  /** The last [months] complete calendar months; the current, partial month is left out. */
  fun monthly(
      records: List<EnrolmentRecord>,
      courseKeys: Set<String>?,
      today: LocalDate,
      months: Int = 24,
  ): List<MonthStats> {
    val scoped = records.filter { courseKeys == null || it.courseKey in courseKeys }
    val completions = scoped.mapNotNull { it.completedOn }.groupingBy { YearMonth.from(it) }.eachCount()
    val firstCompletionByLearner =
        scoped
            .filter { it.completedOn != null }
            .groupBy { it.learnerId }
            .mapValues { (_, rows) -> rows.minOf { it.completedOn!! } }
    val newLearners =
        firstCompletionByLearner.values.groupingBy { YearMonth.from(it) }.eachCount()
    val current = YearMonth.from(today)
    return (months downTo 1).map { back ->
      val month = current.minusMonths(back.toLong())
      MonthStats(month, completions[month] ?: 0, newLearners[month] ?: 0)
    }
  }

  fun byCommittee(
      records: List<EnrolmentRecord>,
      courses: List<LearnCourse>,
      today: LocalDate,
  ): List<CommitteeStats> {
    val committeeByKey =
        courses.mapNotNull { c -> c.committeeSlug?.let { MoodleText.normalizeKey(c.shortname) to it } }.toMap()
    val yearAgo = today.minusYears(1)
    val recentByCommittee =
        records
            .filter { it.completedOn?.isAfter(yearAgo) == true }
            .groupBy { committeeByKey[it.courseKey] }
    return Committees.ALL.mapNotNull { committee ->
          val visibleCourses = courses.count { it.visible && it.committeeSlug == committee.slug }
          val recent = recentByCommittee[committee.slug].orEmpty()
          if (visibleCourses == 0 && recent.isEmpty()) return@mapNotNull null
          CommitteeStats(
              committee = committee,
              courses = visibleCourses,
              completionsLastYear = recent.size,
              learnersLastYear = recent.map { it.learnerId }.distinct().size,
          )
        }
        .sortedByDescending { it.completionsLastYear }
  }

  fun courseKeysFor(courses: List<LearnCourse>, committee: Committee): Set<String> =
      courses.filter { it.committeeSlug == committee.slug }.map { MoodleText.normalizeKey(it.shortname) }.toSet()
}

package org.dallasmakerspace.moodle

import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.assertEquals
import org.dallasmakerspace.models.Committees
import org.junit.Test

class LearnStatsCalculatorTest {

  private val today = LocalDate.of(2026, 10, 7)
  private val laser = Committees.findBySlug("laser")!!
  private val woodshop = Committees.findBySlug("woodshop")!!

  private fun course(id: Int, committeeSlug: String?, visible: Boolean = true) =
      LearnCourse(
          id = id,
          shortname = "EX-$id",
          name = "Example $id",
          summary = "",
          url = "https://learn.example.org/course/view.php?id=$id",
          visible = visible,
          categoryName = "Example",
          committeeSlug = committeeSlug,
          committeeName = committeeSlug?.let { Committees.findBySlug(it)?.name },
      )

  private val courses =
      listOf(
          course(1, "laser"),
          course(2, "laser", visible = false),
          course(3, "woodshop"),
          course(4, "woodshop"), // no enrolments yet
          course(5, null),
      )

  private fun rec(courseId: Int, learner: Int, completed: LocalDate?) =
      EnrolmentRecord("ex-$courseId", learner, enrolledOn = null, completedOn = completed)

  private val records =
      listOf(
          rec(1, 1, LocalDate.of(2026, 9, 15)),
          rec(1, 2, LocalDate.of(2026, 10, 1)),
          rec(1, 3, null),
          rec(2, 1, LocalDate.of(2024, 5, 1)), // older than a year
          rec(3, 1, LocalDate.of(2026, 10, 2)),
          rec(3, 4, null),
          rec(5, 5, LocalDate.of(2026, 1, 1)),
      )

  @Test
  fun `course stats count completions, the last 12 months and the latest completion`() {
    val stats = LearnStatsCalculator.courseStats(records, courses, null, today).associateBy { it.courseKey }

    val c1 = stats.getValue("ex-1")
    assertEquals(2, c1.completed)
    assertEquals(2, c1.completionsLastYear)
    assertEquals(LocalDate.of(2026, 10, 1), c1.lastCompleted)

    val c2 = stats.getValue("ex-2") // hidden but has history
    assertEquals(1, c2.completed)
    assertEquals(0, c2.completionsLastYear)

    // Visible course with no completions is still listed, with no last completion.
    assertEquals(0, stats.getValue("ex-4").completed)
    assertEquals(null, stats.getValue("ex-4").lastCompleted)
  }

  @Test
  fun `committee scope keeps only that committee's courses, hidden ones included`() {
    val keys =
        LearnStatsCalculator.courseStats(records, courses, laser, today).map { it.courseKey }.toSet()
    assertEquals(setOf("ex-1", "ex-2"), keys)
  }

  @Test
  fun `monthly counts completions and first-time learners over complete months only`() {
    val months = LearnStatsCalculator.monthly(records, null, today, months = 3)

    assertEquals(
        listOf(YearMonth.of(2026, 7), YearMonth.of(2026, 8), YearMonth.of(2026, 9)), months.map { it.month })
    val sep = months.last()
    assertEquals(1, sep.completions)
    assertEquals(0, sep.newLearners) // learner 1 first completed in 2024

    // October is the current, partial month on [today].
    val nextMonth = LearnStatsCalculator.monthly(records, null, today.plusMonths(1), months = 1).single()
    assertEquals(YearMonth.of(2026, 10), nextMonth.month)
    assertEquals(2, nextMonth.completions)
    assertEquals(1, nextMonth.newLearners) // learner 2
  }

  @Test
  fun `monthly scoped to a committee's course keys`() {
    val keys = LearnStatsCalculator.courseKeysFor(courses, woodshop)
    val oct =
        LearnStatsCalculator.monthly(records, keys, today.plusMonths(1), months = 1).single()
    assertEquals(1, oct.completions)
    assertEquals(1, oct.newLearners) // learner 1's first woodshop completion
  }

  @Test
  fun `by committee totals recent completions and distinct learners`() {
    val stats = LearnStatsCalculator.byCommittee(records, courses, today).associateBy { it.committee.slug }

    val l = stats.getValue("laser")
    assertEquals(2, l.completionsLastYear)
    assertEquals(2, l.learnersLastYear)
    assertEquals(1, l.courses)

    val w = stats.getValue("woodshop")
    assertEquals(1, w.completionsLastYear)
    assertEquals(2, w.courses)

    assertEquals(setOf("laser", "woodshop"), stats.keys)
  }
}

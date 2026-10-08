package org.dallasmakerspace.dataviz.reports.moodle

import java.time.LocalDate
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.dallasmakerspace.config.ConfigRegistry
import org.dallasmakerspace.config.FeatureFlagService
import org.dallasmakerspace.moodle.EnrolmentRecord
import org.dallasmakerspace.moodle.LearnCourse
import org.dallasmakerspace.moodle.MoodleCatalogService
import org.dallasmakerspace.moodle.MoodleStatsService
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class MoodleReportsTest {

  private val featureFlagService = mock<FeatureFlagService>()
  private val catalogService = mock<MoodleCatalogService>()
  private val statsService = mock<MoodleStatsService>()
  private val support = MoodleReportSupport(featureFlagService, catalogService, statsService)

  private val course =
      LearnCourse(
          id = 1,
          shortname = "EX-1",
          name = "Example Laser Course",
          summary = "",
          url = "https://learn.example.org/course/view.php?id=1",
          visible = true,
          categoryName = "Laser",
          committeeSlug = "laser",
          committeeName = "Laser",
      )

  @Before
  fun setUp() = runBlocking<Unit> {
    whenever(featureFlagService.isEnabledForUsername(eq(ConfigRegistry.MOODLE_ENABLED), eq("jdoe")))
        .thenReturn(true)
    whenever(featureFlagService.isEnabledForUsername(eq(ConfigRegistry.MOODLE_ENABLED), eq("asmith")))
        .thenReturn(false)
    whenever(statsService.isConfigured).thenReturn(true)
    whenever(statsService.getRecords())
        .thenReturn(listOf(EnrolmentRecord("ex-1", 1, null, LocalDate.now().minusMonths(1))))
    whenever(catalogService.getAllCourses()).thenReturn(listOf(course))
    Unit
  }

  private fun params(actor: String, committee: String? = null) =
      buildMap {
        put("actorUsername", listOf(actor))
        committee?.let { put("committee", listOf(it)) }
      }

  @Test
  fun `reports refuse when the flag is off for the viewer`() = runBlocking<Unit> {
    val response = MoodleCoursesReport(support).getData(params("asmith"))
    assertEquals("Online course reports are not enabled", response.metadata?.get("error"))
    assertEquals(0, response.data?.size)
  }

  @Test
  fun `unknown committee is an error`() = runBlocking<Unit> {
    val response = MoodleCoursesReport(support).getData(params("jdoe", "no-such-committee"))
    assertEquals("Unknown committee: no-such-committee", response.metadata?.get("error"))
  }

  @Test
  fun `committee-scoped courses report omits the committee column`() = runBlocking<Unit> {
    val response = MoodleCoursesReport(support).getData(params("jdoe", "laser"))

    assertEquals("Laser", response.metadata?.get("Committee"))
    assertEquals(1, response.data?.size)
    assertEquals(
        listOf("Course", "Completions (12 mo)", "Completions (all time)", "Last completed", "Status"),
        response.dataFields.map { it.name })
  }

  @Test
  fun `visible courses without self enrolment are flagged and listed first`() = runBlocking<Unit> {
    val open = course.copy(id = 2, shortname = "EX-2", name = "Example Open Course", selfEnrol = true)
    whenever(catalogService.getAllCourses()).thenReturn(listOf(open, course))
    whenever(statsService.getRecords())
        .thenReturn(
            listOf(
                EnrolmentRecord("ex-2", 1, null, LocalDate.now().minusMonths(1)),
                EnrolmentRecord("ex-2", 2, null, LocalDate.now().minusMonths(1))))

    val response = MoodleCoursesReport(support).getData(params("jdoe"))
    val rows = response.data!!.map { it.values }

    assertEquals("No self-enrolment", rows[0]["Status"]!!.jsonObject["text"]!!.jsonPrimitive.content)
    assertEquals("danger", rows[0]["Status"]!!.jsonObject["variant"]!!.jsonPrimitive.content)
    assertEquals("Open", rows[1]["Status"]!!.jsonObject["text"]!!.jsonPrimitive.content)
    assertEquals("1", response.metadata?.get("No self-enrolment"))
  }

  @Test
  fun `by-committee and monthly reports return rows`() = runBlocking<Unit> {
    val byCommittee = MoodleByCommitteeReport(support).getData(params("jdoe"))
    assertEquals(1, byCommittee.data?.size)

    assertEquals(listOf("Committee", "Members trained"), byCommittee.dataFields.map { it.name })

    val monthly = MoodleCompletionsMonthlyReport(support).getData(params("jdoe"))
    assertEquals(24, monthly.data?.size)
    assertEquals("1", monthly.metadata?.get("Total completions"))
    assertEquals(listOf("Month", "Completions", "First-time learners"), monthly.dataFields.map { it.name })

    val committeeMonthly = MoodleCompletionsMonthlyReport(support).getData(params("jdoe", "laser"))
    assertEquals(listOf("Month", "Completions"), committeeMonthly.dataFields.map { it.name })
  }

  @Test
  fun `stats not configured is an error`() = runBlocking<Unit> {
    whenever(statsService.isConfigured).thenReturn(false)
    val response = MoodleCompletionsMonthlyReport(support).getData(params("jdoe"))
    assertEquals("Moodle stats report is not configured", response.metadata?.get("error"))
  }
}

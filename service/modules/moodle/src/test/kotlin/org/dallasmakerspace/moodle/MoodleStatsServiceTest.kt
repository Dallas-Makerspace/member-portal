package org.dallasmakerspace.moodle

import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.core.LoggerFactory
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.slf4j.Logger

class MoodleStatsServiceTest {

  private fun loggerFactory() =
      mock<LoggerFactory>().also { whenever(it.create(any())).thenReturn(mock<Logger>()) }

  @Test
  fun `toRecords parses dates and replaces usernames with stable anonymous ids`() {
    val records =
        MoodleStatsService.toRecords(
            listOf(
                listOf("EX-1", "jdoe", "1 March 2026, 9:00 AM", "2 March 2026, 9:00 AM"),
                listOf("EX-2", "asmith", "3 March 2026, 9:00 AM", ""),
                listOf("EX-2", "JDoe", "4 March 2026, 9:00 AM", null),
                listOf("", "nobody", "", ""),
            ))

    assertEquals(3, records.size)
    assertEquals("ex-1", records[0].courseKey)
    assertEquals(LocalDate.of(2026, 3, 2), records[0].completedOn)
    assertNull(records[1].completedOn)
    assertEquals(records[0].learnerId, records[2].learnerId)
    assertEquals(setOf(1, 2), records.map { it.learnerId }.toSet())
  }

  @Test
  fun `pages through the report until totalrowcount is reached`() = runBlocking<Unit> {
    val client = mock<IMoodleApiClient>()
    whenever(client.statsReportId).thenReturn(9)
    val row = MoodleReportRow(listOf("EX-1", "jdoe", "1 March 2026", ""))
    whenever(client.getReportPage(9, 0, 1_000))
        .thenReturn(MoodleReportData(rows = List(1_000) { row }, totalrowcount = 1_200))
    whenever(client.getReportPage(9, 1, 1_000))
        .thenReturn(MoodleReportData(rows = List(200) { row }, totalrowcount = 1_200))

    val records = MoodleStatsService(client, loggerFactory()).getRecords()

    assertEquals(1_200, records?.size)
  }

  @Test
  fun `not configured when the report id is zero`() = runBlocking<Unit> {
    val client = mock<IMoodleApiClient>()
    whenever(client.statsReportId).thenReturn(0)
    val service = MoodleStatsService(client, loggerFactory())

    assertEquals(false, service.isConfigured)
    assertNull(service.getRecords())
  }
}

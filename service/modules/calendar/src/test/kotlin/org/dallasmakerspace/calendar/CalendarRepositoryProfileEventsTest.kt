package org.dallasmakerspace.calendar

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.db.master.GenericRepository
import org.dallasmakerspace.db.master.GenericRepository.ColumnInfo
import org.dallasmakerspace.db.master.GenericRepository.QueryResult
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.slf4j.Logger

class CalendarRepositoryProfileEventsTest {
  private val loggerFactory = mock<LoggerFactory> { on { create(any()) } doReturn mock<Logger>() }

  private fun event(id: Int, start: String) =
      EventSummary(id = id, name = "Event $id", eventStart = start, status = "published")

  private fun row(id: Int, start: String, upcoming: Boolean): Map<String, Any?> =
      mapOf(
          "id" to id,
          "name" to "Event $id",
          "event_start_cst" to start,
          "status" to "published",
          "is_upcoming" to if (upcoming) 1L else 0L)

  private fun repository(rows: List<Map<String, Any?>>): Pair<CalendarRepository, GenericRepository> {
    val generic =
        mock<GenericRepository> {
          onBlocking { getReportData(any()) } doReturn QueryResult(rows, emptyList<ColumnInfo>())
        }
    return CalendarRepository(generic, loggerFactory) to generic
  }

  @Test
  fun `upcoming events come soonest first, then past events newest first`() {
    val picked =
        orderProfileEvents(
            listOf(
                false to event(1, "2026-10-16 19:00:00"),
                true to event(2, "2026-11-06 19:00:00"),
                false to event(3, "2026-10-17 13:45:00"),
                true to event(4, "2026-10-21 17:30:00"),
                true to event(5, "2026-10-28 17:30:00")))

    assertEquals(listOf(4, 5, 2, 3, 1), picked.map { it.id })
  }

  @Test
  fun `the repository tags and orders rows from the single query`() = runBlocking {
    // Reported: Oct 28 and Nov 6 are separate sessions of one workshop and both must be present.
    val (repo, _) =
        repository(
            listOf(
                row(1, "2026-10-17 12:00:00", upcoming = false),
                row(2, "2026-11-06 19:00:00", upcoming = true),
                row(3, "2026-10-28 17:30:00", upcoming = true),
                row(4, "2026-10-21 19:00:00", upcoming = true)))

    val events = repo.getEventsOrganizedByMember("jdoe", pastLimit = 5)

    assertEquals(listOf(4, 3, 2, 1), events.map { it.id })
  }

  @Test
  fun `the query takes every upcoming event and a limited number of past ones, and quotes the username`() =
      runBlocking {
        val (repo, generic) = repository(emptyList())

        repo.getEventsOrganizedByMember("o'brien", pastLimit = 4)

        val captor = argumentCaptor<String>()
        verify(generic).getReportData(captor.capture())
        val query = captor.firstValue
        assertTrue(query.contains("e.event_start >= NOW()"), query)
        assertTrue(query.contains("e.event_start < NOW()"), query)
        assertTrue(query.contains("c.ad_username = 'o''brien'"), query)
        assertTrue(query.contains("max_statement_time"))
        assertTrue(query.contains("LIMIT $PROFILE_MAX_UPCOMING_EVENTS)"), query)
        assertTrue(query.contains("LIMIT 4)"), query)
      }
}

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
  fun `a far-off upcoming event is not pushed out by recent past ones`() {
    // Mirrors the report: five recent past/near events used to outrank an event 17 days away.
    val picked =
        pickProfileEvents(
            listOf(
                true to event(1, "2026-11-06 19:00:00"),
                true to event(2, "2026-10-21 19:00:00"),
                false to event(3, "2026-10-17 13:45:00"),
                false to event(4, "2026-10-17 12:00:00"),
                false to event(5, "2026-10-16 19:00:00"),
                false to event(6, "2026-10-10 19:00:00")),
            limit = 5)

    assertEquals(listOf(1, 2, 3, 4, 5), picked.map { it.id })
  }

  @Test
  fun `at most three upcoming events are reserved, the rest of the slots are past ones`() {
    val upcoming = (1..5).map { true to event(it, "2026-11-0$it 10:00:00") }
    // Past rows arrive nearest-first (latest first), as the query returns them.
    val past = (6..9).map { false to event(it, "2026-10-0${10 - it} 10:00:00") }

    val picked = pickProfileEvents(upcoming + past, limit = 5)

    assertEquals(5, picked.size)
    assertEquals(3, picked.count { it.eventStart.startsWith("2026-11") })
    assertEquals(listOf(3, 2, 1, 6, 7), picked.map { it.id })
  }

  @Test
  fun `with no upcoming events the card is all past events`() {
    val past = (1..7).map { false to event(it, "2026-10-${20 - it} 10:00:00") }

    val picked = pickProfileEvents(past, limit = 5)

    assertEquals(listOf(1, 2, 3, 4, 5), picked.map { it.id })
  }

  @Test
  fun `a smaller limit is respected`() {
    val rows = listOf(true to event(1, "2026-11-06 19:00:00"), false to event(2, "2026-10-01 19:00:00"))

    assertEquals(listOf(1), pickProfileEvents(rows, limit = 1).map { it.id })
  }

  @Test
  fun `the repository tags rows and returns the picked events newest first`() = runBlocking {
    val (repo, _) =
        repository(
            listOf(
                row(1, "2026-11-06 19:00:00", upcoming = true),
                row(2, "2026-10-21 19:00:00", upcoming = true),
                row(3, "2026-10-17 12:00:00", upcoming = false)))

    val events = repo.getEventsOrganizedByMember("jdoe", limit = 5)

    assertEquals(listOf(1, 2, 3), events.map { it.id })
    assertEquals("2026-11-06 19:00:00", events.first().eventStart)
  }

  @Test
  fun `the query asks for upcoming and past events separately and quotes the username`() = runBlocking {
    val (repo, generic) = repository(emptyList())

    repo.getEventsOrganizedByMember("o'brien", limit = 5)

    val captor = argumentCaptor<String>()
    verify(generic).getReportData(captor.capture())
    val query = captor.firstValue
    assertTrue(query.contains("e.event_start >= NOW()"), query)
    assertTrue(query.contains("e.event_start < NOW()"), query)
    assertTrue(query.contains("c.ad_username = 'o''brien'"), query)
    assertTrue(query.contains("max_statement_time"))
    assertTrue(query.contains("LIMIT $PROFILE_UPCOMING_EVENTS"), query)
  }
}

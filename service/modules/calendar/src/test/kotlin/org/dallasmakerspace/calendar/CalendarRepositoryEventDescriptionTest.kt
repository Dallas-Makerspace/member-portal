package org.dallasmakerspace.calendar

import kotlin.test.assertEquals
import kotlin.test.assertNull
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
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.slf4j.Logger

class CalendarRepositoryEventDescriptionTest {

  private val loggerFactory = mock<LoggerFactory> { on { create(any()) } doReturn mock<Logger>() }

  private fun repository(generic: GenericRepository) = CalendarRepository(generic, loggerFactory)

  private fun returning(vararg rows: Map<String, Any?>) =
      mock<GenericRepository> {
        onBlocking { getReportData(any()) } doReturn QueryResult(rows.toList(), emptyList<ColumnInfo>())
      }

  private fun row(long: String?, short: String?) =
      mapOf("long_description" to long, "short_description" to short)

  @Test
  fun `prefers the long description`() = runBlocking {
    assertEquals(
        "The long one",
        repository(returning(row(" The long one ", "The short one"))).getEventDescription(1))
  }

  @Test
  fun `falls back to the short description when the long one is empty`() = runBlocking {
    assertEquals("The short one", repository(returning(row(null, "The short one"))).getEventDescription(1))
    assertEquals("The short one", repository(returning(row("  ", "The short one"))).getEventDescription(1))
  }

  @Test
  fun `no event, no text or a lookup failure is null`() = runBlocking {
    assertNull(repository(returning()).getEventDescription(1))
    assertNull(repository(returning(row(null, " "))).getEventDescription(1))
    val failing = mock<GenericRepository> { onBlocking { getReportData(any()) } doThrow RuntimeException("timeout") }
    assertNull(repository(failing).getEventDescription(1))
  }

  @Test
  fun `the query is read-only, bounded and keyed by the numeric event id`() = runBlocking {
    val generic = returning(row("x", "y"))
    repository(generic).getEventDescription(12345)

    val captor = argumentCaptor<String>()
    verify(generic).getReportData(captor.capture())
    val query = captor.firstValue
    assertTrue(query.contains("WHERE e.id = 12345"), query)
    assertTrue(query.contains("max_statement_time"))
    assertTrue(!query.contains("INSERT", ignoreCase = true) && !query.contains("UPDATE", ignoreCase = true))
  }
}

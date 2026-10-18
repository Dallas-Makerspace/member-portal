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

class CalendarRepositoryEventImageTest {

  private val loggerFactory = mock<LoggerFactory> { on { create(any()) } doReturn mock<Logger>() }

  private fun repository(generic: GenericRepository) = CalendarRepository(generic, loggerFactory)

  private fun returning(vararg rows: Map<String, Any?>) =
      mock<GenericRepository> {
        onBlocking { getReportData(any()) } doReturn QueryResult(rows.toList(), emptyList<ColumnInfo>())
      }

  @Test
  fun `builds the web path from the stored folder and file name`() = runBlocking {
    val repo = repository(returning(mapOf("dir" to "webroot/files/42/", "file" to "flyer 1.jpg")))

    assertEquals("/files/42/flyer 1.jpg", repo.getEventImagePath(42))
  }

  @Test
  fun `a folder without a trailing slash or webroot prefix still gives a valid path`() = runBlocking {
    assertEquals(
        "/files/42/a.png",
        repository(returning(mapOf("dir" to "webroot/files/42", "file" to "a.png")))
            .getEventImagePath(42))
    assertEquals(
        "/a.png", repository(returning(mapOf("dir" to "", "file" to "a.png"))).getEventImagePath(42))
  }

  @Test
  fun `no image, a blank file name or a lookup failure is null`() = runBlocking {
    assertNull(repository(returning()).getEventImagePath(1))
    assertNull(repository(returning(mapOf("dir" to "webroot/files/1/", "file" to " "))).getEventImagePath(1))
    val failing = mock<GenericRepository> { onBlocking { getReportData(any()) } doThrow RuntimeException("timeout") }
    assertNull(repository(failing).getEventImagePath(1))
  }

  @Test
  fun `the query is read-only, bounded, public-only, image-only and keyed by the numeric id`() =
      runBlocking {
        val generic = returning(mapOf("dir" to "webroot/files/1/", "file" to "a.png"))
        repository(generic).getEventImagePath(12345)

        val captor = argumentCaptor<String>()
        verify(generic).getReportData(captor.capture())
        val query = captor.firstValue
        assertTrue(query.contains("f.event_id = 12345"), query)
        assertTrue(query.contains("f.`private` = 0"), query)
        assertTrue(query.contains("png|jpe?g|gif"), query)
        assertTrue(query.contains("ORDER BY f.id ASC") && query.contains("LIMIT 1"), query)
        assertTrue(query.contains("max_statement_time"))
        assertTrue(!query.contains("INSERT", ignoreCase = true) && !query.contains("UPDATE", ignoreCase = true))
      }
}

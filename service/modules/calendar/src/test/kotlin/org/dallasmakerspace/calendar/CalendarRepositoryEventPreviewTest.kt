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

class CalendarRepositoryEventPreviewTest {

  private val loggerFactory = mock<LoggerFactory> { on { create(any()) } doReturn mock<Logger>() }

  private fun repository(generic: GenericRepository) = CalendarRepository(generic, loggerFactory)

  private fun returning(vararg rows: Map<String, Any?>) =
      mock<GenericRepository> {
        onBlocking { getReportData(any()) } doReturn QueryResult(rows.toList(), emptyList<ColumnInfo>())
      }

  private fun row(long: String? = null, short: String? = null, image: String? = null) =
      mapOf("long_description" to long, "short_description" to short, "image_path" to image)

  private suspend fun preview(vararg rows: Map<String, Any?>) =
      repository(returning(*rows)).getEventPreviewData(1)

  @Test
  fun `reads the image path and prefers the long description`() = runBlocking {
    val data = preview(row(" The long one ", "The short one", "webroot/files/42/flyer 1.jpg"))

    assertEquals(EventPreviewData("/files/42/flyer 1.jpg", "The long one"), data)
  }

  @Test
  fun `falls back to the short description when the long one is empty`() = runBlocking {
    assertEquals("The short one", preview(row(null, "The short one"))!!.description)
    assertEquals("The short one", preview(row("  ", "The short one"))!!.description)
  }

  @Test
  fun `an event with no image or text has null fields`() = runBlocking {
    assertEquals(EventPreviewData(null, null), preview(row(null, " ", " ")))
  }

  @Test
  fun `only paths under the calendar files folder count as images`() = runBlocking {
    assertEquals("/files/1/a.png", preview(row(image = "webroot/files/1/a.png"))!!.imagePath)
    assertEquals("/files/1/a.png", preview(row(image = "/files/1/a.png"))!!.imagePath)
    assertNull(preview(row(image = "a.png"))!!.imagePath)
    assertNull(preview(row(image = "/etc/passwd.png"))!!.imagePath)
    assertNull(preview(row(image = "webroot/other/a.png"))!!.imagePath)
  }

  @Test
  fun `an unknown event or a lookup failure is null`() = runBlocking {
    assertNull(preview())
    val failing = mock<GenericRepository> { onBlocking { getReportData(any()) } doThrow RuntimeException("timeout") }
    assertNull(repository(failing).getEventPreviewData(1))
  }

  @Test
  fun `the query is read-only, bounded, public-only, image-only and keyed by the numeric id`() =
      runBlocking {
        val generic = returning(row("x", "y", "webroot/files/1/a.png"))
        repository(generic).getEventPreviewData(12345)

        val captor = argumentCaptor<String>()
        verify(generic).getReportData(captor.capture())
        val query = captor.firstValue
        assertTrue(query.contains("WHERE e.id = 12345"), query)
        assertTrue(query.contains("f.`private` = 0"), query)
        assertTrue(query.contains("png|jpe?g|gif"), query)
        assertTrue(query.contains("ORDER BY f.id ASC"), query)
        assertTrue(query.contains("LEFT(e.long_description, 1000)"), query)
        assertTrue(query.contains("max_statement_time"))
        assertTrue(!query.contains("INSERT", ignoreCase = true) && !query.contains("UPDATE", ignoreCase = true))
      }
}

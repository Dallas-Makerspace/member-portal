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

class CalendarRepositoryEventPrerequisiteTest {

  private val loggerFactory = mock<LoggerFactory> { on { create(any()) } doReturn mock<Logger>() }

  private fun repository(generic: GenericRepository) = CalendarRepository(generic, loggerFactory)

  private fun returning(vararg rows: Map<String, Any?>) =
      mock<GenericRepository> {
        onBlocking { getReportData(any()) } doReturn QueryResult(rows.toList(), emptyList<ColumnInfo>())
      }

  @Test
  fun `reads the prerequisite group and parent event`() = runBlocking {
    val repo = repository(returning(mapOf("ad_group" to "Laser Basics", "part_of_id" to 77L)))

    assertEquals(EventPrerequisite("Laser Basics", 77L), repo.getEventPrerequisite(12345))
  }

  @Test
  fun `an event with no prerequisite or parent has null fields`() = runBlocking {
    val repo = repository(returning(mapOf("ad_group" to null, "part_of_id" to null)))

    assertEquals(EventPrerequisite(null, null), repo.getEventPrerequisite(12345))
  }

  @Test
  fun `a blank group is treated as none and surrounding spaces are trimmed`() = runBlocking {
    assertNull(repository(returning(mapOf("ad_group" to "  ", "part_of_id" to null)))
        .getEventPrerequisite(1)!!.adGroup)
    assertEquals(
        "Laser Basics",
        repository(returning(mapOf("ad_group" to " Laser Basics ", "part_of_id" to null)))
            .getEventPrerequisite(1)!!.adGroup)
  }

  @Test
  fun `an unknown event or a lookup failure is null`() = runBlocking {
    assertNull(repository(returning()).getEventPrerequisite(1))
    val failing = mock<GenericRepository> { onBlocking { getReportData(any()) } doThrow RuntimeException("timeout") }
    assertNull(repository(failing).getEventPrerequisite(1))
  }

  @Test
  fun `the query is read-only, bounded and keyed by the numeric event id`() = runBlocking {
    val generic = returning(mapOf("ad_group" to "Laser Basics", "part_of_id" to null))
    repository(generic).getEventPrerequisite(12345)

    val captor = argumentCaptor<String>()
    verify(generic).getReportData(captor.capture())
    val query = captor.firstValue
    assertTrue(query.contains("WHERE e.id = 12345"), query)
    assertTrue(query.contains("max_statement_time"))
    assertTrue(!query.contains("INSERT", ignoreCase = true) && !query.contains("UPDATE", ignoreCase = true))
  }
}

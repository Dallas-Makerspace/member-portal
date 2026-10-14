package org.dallasmakerspace.calendar

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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

class CalendarRepositoryReferencesTest {

  private val loggerFactory =
      mock<LoggerFactory> { on { create(any()) } doReturn mock<Logger>() }

  private fun repository(result: QueryResult?): Pair<CalendarRepository, GenericRepository> {
    val generic = mock<GenericRepository> { onBlocking { getReportData(any()) } doReturn result }
    return CalendarRepository(generic, loggerFactory) to generic
  }

  private fun rowResult(vararg values: Pair<String, Any?>) =
      QueryResult(listOf(mapOf(*values)), emptyList<ColumnInfo>())

  private fun capturedQuery(generic: GenericRepository): String = runBlocking {
    val captor = argumentCaptor<String>()
    verify(generic).getReportData(captor.capture())
    captor.firstValue
  }

  @Test
  fun `counts come back from the single result row`() = runBlocking {
    val (repo, _) =
        repository(
            rowResult("prerequisites" to 2L, "upcoming_events" to 3L, "past_events" to 5L))
    assertEquals(PrerequisiteReferences(2, 3, 5), repo.countPrerequisiteReferences("Example Group"))
  }

  @Test
  fun `the query is read-only, case-insensitive and quotes the name`() = runBlocking {
    val (repo, generic) =
        repository(rowResult("prerequisites" to 0L, "upcoming_events" to 0L, "past_events" to 0L))
    repo.countPrerequisiteReferences("  O'Example Group ")
    val query = capturedQuery(generic)
    assertTrue(query.contains("LOWER(TRIM(p.ad_group)) = LOWER('O''Example Group')"), query)
    assertTrue(query.contains("max_statement_time"))
    assertFalse(query.contains("INSERT", ignoreCase = true) || query.contains("UPDATE", ignoreCase = true))
  }

  @Test
  fun `an injection attempt stays inside the literal`() = runBlocking {
    val (repo, generic) =
        repository(rowResult("prerequisites" to 0L, "upcoming_events" to 0L, "past_events" to 0L))
    repo.countPrerequisiteReferences("x' OR '1'='1")
    val query = capturedQuery(generic)
    assertTrue(query.contains("LOWER('x'' OR ''1''=''1')"), query)
  }

  @Test
  fun `backslashes and control characters are refused`() {
    val (repo, _) = repository(null)
    assertFailsWith<IllegalArgumentException> {
      runBlocking { repo.countPrerequisiteReferences("a\\b") }
    }
    assertFailsWith<IllegalArgumentException> {
      runBlocking { repo.countPrerequisiteReferences("a\u0000b") }
    }
  }

  @Test
  fun `a missing result or a database error throws instead of reading as zero`() {
    val (empty, _) = repository(null)
    assertFailsWith<IllegalStateException> {
      runBlocking { empty.countPrerequisiteReferences("Example Group") }
    }
    val failing =
        CalendarRepository(
            mock<GenericRepository> {
              onBlocking { getReportData(any()) } doThrow RuntimeException("max_statement_time exceeded")
            },
            loggerFactory)
    assertFailsWith<RuntimeException> {
      runBlocking { failing.countPrerequisiteReferences("Example Group") }
    }
  }

  @Test
  fun `a row without the expected columns throws`() {
    val (repo, _) = repository(rowResult("prerequisites" to 1L))
    assertFailsWith<IllegalStateException> {
      runBlocking { repo.countPrerequisiteReferences("Example Group") }
    }
  }

  @Test
  fun `sqlStringLiteral doubles quotes only`() {
    assertEquals("'plain'", sqlStringLiteral("plain"))
    assertEquals("'it''s'", sqlStringLiteral("it's"))
  }
}

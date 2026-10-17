package org.dallasmakerspace.smartwaiver

import java.sql.SQLException
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.core.DBMasterConnection
import org.dallasmakerspace.core.LoggerFactory
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.transaction

class SmartWaiverCheckinSyncTest {
  private val repo = SmartWaiverMirrorRepository()

  /** Serves scripted pages, one per call, and records the start of each request. */
  private class ScriptedClient(private val pages: List<SmartwaiverCheckinPage>) : ISmartwaiverApiClient {
    val requestedFrom = mutableListOf<LocalDateTime>()

    override suspend fun getCheckins(from: LocalDateTime, to: LocalDateTime): SmartwaiverCheckinPage {
      requestedFrom += from
      return pages[requestedFrom.size - 1]
    }

    override suspend fun getWaivers(fromDate: LocalDate, toDate: LocalDate) = emptyList<WaiverSigningData>()

    override suspend fun getWaiverDetails(fromDate: LocalDate, toDate: LocalDate) = emptyList<SmartwaiverSummary>()

    override suspend fun getWaiversByName(firstName: String, lastName: String) = emptyList<SmartwaiverSummary>()

    override suspend fun getWaiver(waiverId: String): SmartwaiverFullWaiver = error("not used")

    override suspend fun getWaiverPhotos(waiverId: String) = emptyList<SmartwaiverPhotoEntry>()

    override suspend fun countWaivers(fromDate: LocalDate, toDate: LocalDate) = 0

    override fun stats() = ApiClientStats(0, 0)
  }

  @BeforeTest
  fun setUp() {
    DBMasterConnection.db = SmartWaiverMirrorRepositoryTest.db
    transaction(SmartWaiverMirrorRepositoryTest.db) { SmartWaiverCheckinsTable.deleteAll() }
  }

  private fun checkin(id: Long, date: String) = SmartwaiverCheckin(id, "fakewaiver01", date, null, 0, "t", 0)

  private fun sync(vararg pages: SmartwaiverCheckinPage): Pair<SmartWaiverCheckinSync, ScriptedClient> {
    val client = ScriptedClient(pages.toList())
    return SmartWaiverCheckinSync(client, repo, LoggerFactory()) to client
  }

  private val start = LocalDateTime.of(2014, 1, 1, 0, 0)
  private val end = LocalDateTime.of(2014, 12, 31, 0, 0)

  @Test
  fun `a page that says there is more is followed from its last date`() {
    val (sync, client) =
        sync(
            SmartwaiverCheckinPage(listOf(checkin(1, "2014-02-01 10:00:00"), checkin(2, "2014-02-03 10:00:00")), true),
            SmartwaiverCheckinPage(listOf(checkin(2, "2014-02-03 10:00:00"), checkin(3, "2014-02-05 10:00:00")), false))

    val added = runBlocking { sync.syncRange(start, end) }

    assertEquals(3, added, "the overlapping check-in is stored once")
    assertEquals(listOf(start, LocalDateTime.of(2014, 2, 3, 10, 0)), client.requestedFrom)
    assertEquals(3, runBlocking { repo.countCheckins() })
  }

  @Test
  fun `a full page inside one second moves one second on instead of looping`() {
    val (sync, client) =
        sync(
            SmartwaiverCheckinPage(listOf(checkin(1, "2014-02-01 10:00:00"), checkin(2, "2014-02-01 10:00:00")), true),
            SmartwaiverCheckinPage(listOf(checkin(3, "2014-02-01 10:00:05")), false))

    val added = runBlocking { sync.syncRange(LocalDateTime.of(2014, 2, 1, 10, 0, 0), end) }

    assertEquals(3, added)
    // The second request starts one second later, past the shared second, so it can make progress.
    assertEquals(
        listOf(LocalDateTime.of(2014, 2, 1, 10, 0, 0), LocalDateTime.of(2014, 2, 1, 10, 0, 1)),
        client.requestedFrom)
  }

  @Test
  fun `a page with more to come but an unreadable date is an error, not the end of the history`() {
    val (sync, _) = sync(SmartwaiverCheckinPage(listOf(checkin(1, "not a date")), true))

    val e = assertFailsWith<IllegalStateException> { runBlocking { sync.syncRange(start, end) } }

    assertContains(e.message.orEmpty(), "can't be read")
  }

  @Test
  fun `an empty page that claims there is more is an error`() {
    val (sync, _) = sync(SmartwaiverCheckinPage(emptyList(), true))

    assertFailsWith<IllegalStateException> { runBlocking { sync.syncRange(start, end) } }
  }

  @Test
  fun `dates that go backwards are an error`() {
    val (sync, _) = sync(SmartwaiverCheckinPage(listOf(checkin(1, "2013-12-31 10:00:00")), true))

    val e = assertFailsWith<IllegalStateException> { runBlocking { sync.syncRange(start, end) } }

    assertContains(e.message.orEmpty(), "backwards")
  }

  @Test
  fun `the last page may be empty or short without an error`() {
    val (sync, _) = sync(SmartwaiverCheckinPage(emptyList(), false))

    assertEquals(0, runBlocking { sync.syncRange(start, end) })
  }

  @Test
  fun `safe messages hide the values in a database error`() {
    val cause = SQLException("Duplicate entry 'Jane Doe' for key", "23000", 1062)
    val e = RuntimeException("wrapped: INSERT ... VALUES('Jane','Doe','jane@example.invalid')", cause)

    val msg = e.safeMessage()

    assertFalse(msg.contains("Jane") || msg.contains("example.invalid"), msg)
    assertContains(msg, "23000")
    assertEquals("plain failure", IllegalStateException("plain failure").safeMessage())
    assertTrue(SQLException("x", "23505").let { RuntimeException(it).isDuplicateKey() })
    assertFalse(RuntimeException("no sql here").isDuplicateKey())
  }
}

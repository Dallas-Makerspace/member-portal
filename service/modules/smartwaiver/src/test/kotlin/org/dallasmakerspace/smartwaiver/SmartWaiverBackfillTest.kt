package org.dallasmakerspace.smartwaiver

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.dallasmakerspace.core.DBMasterConnection
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.storage.InMemoryObjectStore
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

class SmartWaiverBackfillTest {

  /** A clock the test moves by hand; `sleep` advances it so waiting costs no real time. */
  private class TestClock(var now: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock {
      val outer = this
      return object : Clock() {
        override fun getZone() = zone

        override fun withZone(zone: ZoneId) = outer.withZone(zone)

        override fun instant() = outer.instant()
      }
    }

    override fun instant(): Instant = now
  }

  /** Counts every call as one API request, like the real client does. */
  private class FakeClient(
      val summariesByStart: Map<LocalDate, List<SmartwaiverSummary>>,
      var failWaivers: Boolean = false,
      /** When set, the first search call announces itself and then waits to be released. */
      val hold: CompletableDeferred<Unit>? = null,
  ) : ISmartwaiverApiClient {
    val requests = AtomicLong()
    val entered = CompletableDeferred<Unit>()

    override suspend fun getWaivers(fromDate: LocalDate, toDate: LocalDate): List<WaiverSigningData> = emptyList()

    override suspend fun getWaiverDetails(fromDate: LocalDate, toDate: LocalDate): List<SmartwaiverSummary> {
      requests.incrementAndGet()
      if (hold != null && entered.complete(Unit)) hold.await()
      return summariesByStart[fromDate].orEmpty()
    }

    override suspend fun getWaiversByName(firstName: String, lastName: String) = emptyList<SmartwaiverSummary>()

    override suspend fun getWaiver(waiverId: String): SmartwaiverFullWaiver {
      requests.incrementAndGet()
      if (failWaivers) throw SmartwaiverApiException("api down")
      return SmartwaiverFullWaiver(waiverId = waiverId, firstName = "Fake", lastName = "User", createdOn = "2014-01-05 10:00:00", photos = 0)
    }

    override suspend fun getWaiverPhotos(waiverId: String) = emptyList<SmartwaiverPhotoEntry>()

    override suspend fun getCheckins(from: LocalDateTime, to: LocalDateTime): SmartwaiverCheckinPage {
      requests.incrementAndGet()
      return SmartwaiverCheckinPage(listOf(SmartwaiverCheckin(1, "fakewaiver01", "2014-01-06 09:00:00", null, 0, "t", 0)), false)
    }

    override suspend fun countWaivers(fromDate: LocalDate, toDate: LocalDate) = 0

    override fun stats() = ApiClientStats(requests.get(), 0)
  }

  private val repo = SmartWaiverMirrorRepository()
  // 2014-02-20 12:00 UTC (6 am Chicago): two monthly windows, Jan and Feb.
  private val clock = TestClock(Instant.parse("2014-02-20T12:00:00Z"))
  private val sleeps = mutableListOf<Long>()

  private val created = mutableListOf<SmartWaiverBackfill>()

  @AfterTest
  fun tearDown() {
    runBlocking { created.forEach { it.stop() } }
  }

  @BeforeTest
  fun setUp() {
    DBMasterConnection.db = SmartWaiverMirrorRepositoryTest.db
    transaction(SmartWaiverMirrorRepositoryTest.db) {
      SmartWaiverBackfillStateTable.deleteAll()
      SmartWaiverCheckinsTable.deleteAll()
      SmartWaiverPhotosTable.deleteAll()
      SmartWaiverParticipantsTable.deleteAll()
      SmartWaiverMirrorTable.deleteAll()
    }
  }

  private fun summary(id: String) =
      SmartwaiverSummary(waiverId = id, templateId = "t", title = "T", createdOn = "2014-01-05 10:00:00", firstName = "F", lastName = "L")

  private fun backfill(client: FakeClient, budget: Int = 1000): SmartWaiverBackfill {
    val service = SmartWaiverMirrorService(client, repo, InMemoryObjectStore(), LoggerFactory())
    return created.add(newBackfill(client, service, budget)).let { created.last() }
  }

  private fun newBackfill(client: FakeClient, service: SmartWaiverMirrorService, budget: Int): SmartWaiverBackfill {
    return SmartWaiverBackfill(
        client, repo, service, SmartWaiverCheckinSync(client, repo, LoggerFactory()), LoggerFactory(), budget, 3_000L, clock,
        sleep = {
          sleeps += it
          clock.now = clock.now.plusMillis(it)
        })
  }

  private fun awaitState(b: SmartWaiverBackfill, vararg states: RunState): BackfillProgress =
      runBlocking {
        withTimeout(20_000) {
          while (b.status().state !in states) delay(10)
          b.status()
        }
      }

  private fun twoMonths() =
      FakeClient(
          mapOf(
              LocalDate.of(2014, 1, 1) to listOf(summary("fakewaiver01"), summary("fakewaiver02")),
              LocalDate.of(2014, 2, 1) to listOf(summary("fakewaiver03"))))

  private lateinit var heldClient: FakeClient

  private fun twoMonthsHeld(hold: CompletableDeferred<Unit>): FakeClient =
      FakeClient(
              mapOf(
                  LocalDate.of(2014, 1, 1) to listOf(summary("fakewaiver01"), summary("fakewaiver02")),
                  LocalDate.of(2014, 2, 1) to listOf(summary("fakewaiver03"))),
              hold = hold)
          .also { heldClient = it }

  @Test
  fun `a full run discovers, mirrors every waiver, syncs check-ins and finishes`() {
    val b = backfill(twoMonths())

    b.start()
    val done = awaitState(b, RunState.DONE, RunState.FAILED)

    assertEquals(RunState.DONE, done.state, done.lastError)
    assertEquals(3, done.discovered)
    assertEquals(3, done.mirrored)
    assertEquals(0, runBlocking { repo.countNeedingMirror() })
    assertEquals(1, runBlocking { repo.countCheckins() })
    assertEquals("DONE", runBlocking { repo.getState("run") }?.status)
    assertTrue(sleeps.all { it >= 3_000L })
  }

  @Test
  fun `running it again after it finished does no new work`() {
    val client = twoMonths()
    val b = backfill(client)
    b.start()
    awaitState(b, RunState.DONE)
    val callsAfterFirst = client.requests.get()

    b.start()
    awaitState(b, RunState.DONE)

    assertEquals(callsAfterFirst, client.requests.get())
  }

  @Test
  fun `when the day's budget is spent it sleeps until the next Chicago midnight and carries on`() {
    val b = backfill(twoMonths(), budget = 2)

    b.start()
    val done = awaitState(b, RunState.DONE, RunState.FAILED)

    assertEquals(RunState.DONE, done.state, done.lastError)
    // 12:00 UTC is 06:00 in Chicago, so the first wait is about 18 hours (more than 17).
    assertTrue(sleeps.any { it > 17 * 3_600_000L }, "sleeps: $sleeps")
    assertEquals(3, done.mirrored)
  }

  @Test
  fun `pausing holds the run at the next waiver and resuming finishes it`() {
    val hold = CompletableDeferred<Unit>()
    val b = backfill(twoMonthsHeld(hold))

    b.start()
    runBlocking { withTimeout(10_000) { heldClient.entered.await() } }
    b.pause()
    hold.complete(Unit)
    awaitState(b, RunState.PAUSED)
    // The window already in flight (2 waivers) finishes; nothing after it has started.
    assertEquals(2, runBlocking { repo.countWaivers() })
    assertEquals(2, runBlocking { repo.countNeedingMirror() })

    b.resume()
    val done = awaitState(b, RunState.DONE, RunState.FAILED)

    assertEquals(RunState.DONE, done.state, done.lastError)
    assertEquals(3, done.mirrored)
  }

  @Test
  fun `ten failures in a row stop the run instead of burning the budget`() {
    val client =
        FakeClient(mapOf(LocalDate.of(2014, 1, 1) to (1..12).map { summary("fakewaiver%02d".format(it)) }), failWaivers = true)
    val b = backfill(client)

    b.start()
    val done = awaitState(b, RunState.DONE, RunState.FAILED)

    assertEquals(RunState.FAILED, done.state)
    assertTrue(done.lastError.orEmpty().contains("in a row"), done.lastError)
    assertEquals(10, b.recentErrors().size)
    assertEquals("FAILED", runBlocking { repo.getState("run") }?.status)
  }

  @Test
  fun `a second start while running is refused`() {
    val hold = CompletableDeferred<Unit>()
    val b = backfill(twoMonthsHeld(hold))

    assertEquals(StartResult.STARTED, b.start())
    runBlocking { withTimeout(10_000) { heldClient.entered.await() } }
    assertEquals(StartResult.ALREADY_RUNNING, b.start())

    hold.complete(Unit)
    awaitState(b, RunState.DONE)
  }

  @Test
  fun `stopping ends the run and saves it as idle so a restart does not resume it`() {
    val hold = CompletableDeferred<Unit>()
    val b = backfill(twoMonthsHeld(hold))
    b.start()
    runBlocking { withTimeout(10_000) { heldClient.entered.await() } }

    runBlocking { b.stop() }

    assertEquals(RunState.IDLE, b.status().state)
    assertEquals("IDLE", runBlocking { repo.getState("run") }?.status)
  }

  @Test
  fun `eta covers today's leftover budget, the wait for midnight, and whole days`() {
    // 50 calls left, 30 used of 100 today, 3 s per call: all fits today.
    assertEquals(150, etaSeconds(50, 30, 100, 3.0, 10_000))
    // 250 calls left, 0 used of 100: 100 today, then midnight in 5000 s, a full day, then 50 calls.
    assertEquals(5_000 + 86_400 + 150, etaSeconds(250, 0, 100, 3.0, 5_000))
    assertEquals(0, etaSeconds(0, 0, 100, 3.0, 5_000))
    // Budget already spent: wait for midnight, then the calls.
    assertEquals(7_200 + 30, etaSeconds(10, 100, 100, 3.0, 7_200))
  }

  @Test
  fun `the stream starts with the current progress`() {
    val b = backfill(twoMonths())
    b.start()
    awaitState(b, RunState.DONE)

    val frame = runBlocking { withTimeout(5_000) { b.frames().first { it.startsWith("data: ") } } }

    assertTrue(frame.contains("\"state\":\"DONE\""), frame)
  }
}

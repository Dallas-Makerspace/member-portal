package org.dallasmakerspace.smartwaiver

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
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
      /** Waivers whose fetch always fails. */
      var failAlways: Set<String> = emptySet(),
      /** Waivers whose first fetch fails and later ones work. */
      val failOnce: MutableSet<String> = mutableSetOf(),
      /** Waivers SmartWaiver answers 404 for. */
      val gone: Set<String> = emptySet(),
      /** The first search calls fail this many times, with [searchError]. */
      var failSearches: Int = 0,
      val searchError: String = "temporary problem",
      /** When set, the first search call announces itself and then waits to be released. */
      val hold: CompletableDeferred<Unit>? = null,
      /** When set, the first waiver fetch announces itself and then waits to be released. */
      val holdWaiver: CompletableDeferred<Unit>? = null,
  ) : ISmartwaiverApiClient {
    val requests = AtomicLong()
    val searches = AtomicInteger()
    val entered = CompletableDeferred<Unit>()
    val enteredWaiver = CompletableDeferred<Unit>()

    override suspend fun getWaivers(fromDate: LocalDate, toDate: LocalDate): List<WaiverSigningData> = emptyList()

    override suspend fun getWaiverDetails(fromDate: LocalDate, toDate: LocalDate): List<SmartwaiverSummary> {
      requests.incrementAndGet()
      searches.incrementAndGet()
      if (hold != null && entered.complete(Unit)) hold.await()
      if (failSearches > 0) {
        failSearches--
        throw SmartwaiverApiException(searchError)
      }
      return summariesByStart[fromDate].orEmpty()
    }

    override suspend fun getWaiversByName(firstName: String, lastName: String) = emptyList<SmartwaiverSummary>()

    override suspend fun getWaiver(waiverId: String): SmartwaiverFullWaiver {
      requests.incrementAndGet()
      if (holdWaiver != null && enteredWaiver.complete(Unit)) holdWaiver.await()
      if (waiverId in gone) throw SmartwaiverNotFoundException("Waiver not found: $waiverId")
      if (waiverId in failAlways) throw SmartwaiverApiException("api down")
      if (failOnce.remove(waiverId)) throw SmartwaiverApiException("blip")
      return SmartwaiverFullWaiver(waiverId = waiverId, firstName = "Fake", lastName = "User", createdOn = "2014-01-05 10:00:00", photos = 0)
    }

    override suspend fun getWaiverPhotos(waiverId: String) = emptyList<SmartwaiverPhotoEntry>()

    override suspend fun getCheckins(from: LocalDateTime, to: LocalDateTime): SmartwaiverCheckinPage {
      requests.incrementAndGet()
      return SmartwaiverCheckinPage(listOf(SmartwaiverCheckin(1, "fakewaiver01", "2014-01-06 09:00:00", null, 0, "t", 0)), false)
    }

    override suspend fun countWaivers(fromDate: LocalDate, toDate: LocalDate) =
        if (fromDate.monthValue == 1 && fromDate.dayOfMonth == 1) 300 else 0

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

  private fun backfill(client: FakeClient, budget: Int = 1000, owner: String = UUID.randomUUID().toString()): SmartWaiverBackfill {
    val service = SmartWaiverMirrorService(client, repo, InMemoryObjectStore(), LoggerFactory())
    val b =
        SmartWaiverBackfill(
            client, repo, service, SmartWaiverCheckinSync(client, repo, LoggerFactory()), LoggerFactory(), budget, 3_000L, clock,
            sleep = {
              sleeps += it
              clock.now = clock.now.plusMillis(it)
            },
            owner = owner)
    created += b
    return b
  }

  private fun awaitState(b: SmartWaiverBackfill, vararg states: RunState): BackfillProgress =
      runBlocking {
        withTimeout(20_000) {
          while (b.status().state !in states) delay(10)
          b.status()
        }
      }

  private fun threeWaivers() =
      mapOf(
          LocalDate.of(2014, 1, 1) to listOf(summary("fakewaiver01"), summary("fakewaiver02")),
          LocalDate.of(2014, 2, 1) to listOf(summary("fakewaiver03")))

  private fun twoMonths() = FakeClient(threeWaivers())

  private lateinit var heldClient: FakeClient

  private fun twoMonthsHeld(hold: CompletableDeferred<Unit>): FakeClient =
      FakeClient(threeWaivers(), hold = hold).also { heldClient = it }

  private fun runRow() = runBlocking { repo.getState("run") }

  private fun thinRows() =
      runBlocking { repo.insertMissingSummaries(listOf(summary("fakewaiver01"), summary("fakewaiver02"), summary("fakewaiver03"))) }

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
    assertEquals("DONE", runRow()?.status)
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
  fun `a pause is saved, and after a restart resume starts the paused run again`() {
    val hold = CompletableDeferred<Unit>()
    val first = backfill(twoMonthsHeld(hold))
    first.start()
    runBlocking { withTimeout(10_000) { heldClient.entered.await() } }
    first.pause()
    hold.complete(Unit)
    awaitState(first, RunState.PAUSED)
    assertEquals("PAUSED", runRow()?.status)

    // A new container: it finds the saved pause, resumes the run paused, and `resume` lets it go.
    val second = backfill(twoMonths())
    runBlocking { second.resumeIfInterrupted() }
    awaitState(second, RunState.PAUSED)
    assertEquals("resuming", runBlocking { second.resumeOrStart() })
    val done = awaitState(second, RunState.DONE, RunState.FAILED)

    assertEquals(RunState.DONE, done.state, done.lastError)
    assertEquals("nothing is paused", runBlocking { second.resumeOrStart() })
  }

  @Test
  fun `resume with no job running restarts a saved pause`() {
    runBlocking { repo.saveState(BackfillState("run", "PAUSED", "gone-owner", 0, null, 0, null, null, null, null, null)) }
    val fresh = backfill(twoMonths())

    assertEquals("started again from the saved pause", runBlocking { fresh.resumeOrStart() })
    val done = awaitState(fresh, RunState.DONE, RunState.FAILED)

    assertEquals(RunState.DONE, done.state, done.lastError)
  }

  @Test
  fun `ten failures in a row are retried with growing waits, then the run fails`() {
    val ids = (1..12).map { "fakewaiver%02d".format(it) }
    val client = FakeClient(mapOf(LocalDate.of(2014, 1, 1) to ids.map { summary(it) }), failAlways = ids.toSet())
    val b = backfill(client)

    b.start()
    val done = awaitState(b, RunState.DONE, RunState.FAILED)

    assertEquals(RunState.FAILED, done.state)
    assertTrue(done.lastError.orEmpty().contains("in a row"), done.lastError)
    assertTrue(b.recentErrors().size >= 10)
    assertTrue(30_000L in sleeps && 60_000L in sleeps && 600_000L in sleeps, "waits: ${sleeps.filter { it >= 30_000 }}")
    assertEquals("FAILED", runRow()?.status)
  }

  @Test
  fun `a temporary error is waited out and the run carries on`() {
    val client = FakeClient(threeWaivers(), failSearches = 2)
    val b = backfill(client)

    b.start()
    val done = awaitState(b, RunState.DONE, RunState.FAILED)

    assertEquals(RunState.DONE, done.state, done.lastError)
    assertEquals(3, done.mirrored)
    assertTrue(30_000L in sleeps && 60_000L in sleeps, "waits: ${sleeps.filter { it >= 30_000 }}")
  }

  @Test
  fun `a rejected API key fails the run at once instead of retrying`() {
    val client = FakeClient(threeWaivers(), failSearches = 99, searchError = "Unauthorized: Invalid API key")
    val b = backfill(client)

    b.start()
    val done = awaitState(b, RunState.DONE, RunState.FAILED)

    assertEquals(RunState.FAILED, done.state)
    assertTrue(sleeps.none { it >= 30_000L }, "no retry wait expected: $sleeps")
  }

  @Test
  fun `waivers SmartWaiver no longer has are marked gone and do not count as failures`() {
    val client = FakeClient(threeWaivers(), gone = setOf("fakewaiver01", "fakewaiver02", "fakewaiver03"))
    val b = backfill(client)

    b.start()
    val done = awaitState(b, RunState.DONE, RunState.FAILED)

    assertEquals(RunState.DONE, done.state, done.lastError)
    assertEquals(3, done.gone)
    assertEquals(0, done.failed)
    assertEquals(0, runBlocking { repo.countNeedingMirror() })
    assertEquals(3, runBlocking { repo.countGone() })
    assertEquals("DONE", runBlocking { repo.getState("enrich") }?.status)
  }

  @Test
  fun `a waiver that failed once is tried again before the step is called done`() {
    val client = FakeClient(threeWaivers(), failOnce = mutableSetOf("fakewaiver02"))
    val b = backfill(client)

    b.start()
    val done = awaitState(b, RunState.DONE, RunState.FAILED)

    assertEquals(RunState.DONE, done.state, done.lastError)
    assertEquals(3, done.mirrored)
    assertEquals(0, runBlocking { repo.countNeedingMirror() })
  }

  @Test
  fun `retryFailed re-runs only the waiver copying and leaves discovery alone`() {
    val client = FakeClient(threeWaivers(), failAlways = setOf("fakewaiver02"))
    val b = backfill(client)
    b.start()
    awaitState(b, RunState.DONE)
    assertEquals(1, runBlocking { repo.countNeedingMirror() })
    val searchesBefore = client.searches.get()
    client.failAlways = emptySet()

    b.start(retryFailed = true)
    val done = awaitState(b, RunState.DONE, RunState.FAILED)

    assertEquals(RunState.DONE, done.state, done.lastError)
    assertEquals(0, runBlocking { repo.countNeedingMirror() })
    assertEquals(searchesBefore, client.searches.get(), "discovery must not run again")
  }

  @Test
  fun `a trial mirrors only the limit, skips discovery, and a full run carries on from there`() {
    thinRows()
    val client = twoMonths()
    val b = backfill(client)

    b.start(limit = 2)
    val trial = awaitState(b, RunState.DONE, RunState.FAILED)

    assertEquals(RunState.DONE, trial.state, trial.lastError)
    assertEquals(2, trial.mirrored)
    assertEquals(1, runBlocking { repo.countNeedingMirror() })
    assertTrue(trial.message.orEmpty().contains("Trial"), trial.message)
    assertEquals(2, trial.limit)
    assertEquals(1, trial.stageCount)
    assertEquals(1, trial.plan.size)
    assertEquals(2, trial.stageTotal)
    assertEquals(0, runBlocking { repo.countCheckins() })
    assertEquals(0, client.requests.get() - 2, "only the two waiver fetches, no counting, search or check-in calls")
    assertEquals("RUNNING", runBlocking { repo.getState("enrich") }?.status)
    assertEquals(null, runBlocking { repo.getState("discover") })

    b.start()
    val full = awaitState(b, RunState.DONE, RunState.FAILED)

    assertEquals(RunState.DONE, full.state, full.lastError)
    assertEquals(1, full.mirrored)
    assertEquals(0, runBlocking { repo.countNeedingMirror() })
    assertEquals("DONE", runBlocking { repo.getState("enrich") }?.status)
    assertEquals(1, runBlocking { repo.countCheckins() })
  }

  @Test
  fun `a trial that runs out of waivers still does not mark the step done`() {
    thinRows()
    val b = backfill(twoMonths())

    b.start(limit = 50)
    val trial = awaitState(b, RunState.DONE, RunState.FAILED)

    assertEquals(3, trial.mirrored)
    assertEquals("RUNNING", runBlocking { repo.getState("enrich") }?.status)
  }

  @Test
  fun `a trial is saved as TRIAL so a restart never turns it into a full run`() {
    thinRows()
    val hold = CompletableDeferred<Unit>()
    val client = FakeClient(threeWaivers(), holdWaiver = hold)
    val b = backfill(client)
    b.start(limit = 3)
    runBlocking { withTimeout(10_000) { client.enteredWaiver.await() } }

    assertEquals("TRIAL", runRow()?.status)
    val afterDeploy = backfill(twoMonths())
    runBlocking { afterDeploy.resumeIfInterrupted() }

    assertEquals(RunState.IDLE, afterDeploy.status().state, "a trial must not be resumed")
    hold.complete(Unit)
    awaitState(b, RunState.DONE)
  }

  @Test
  fun `only one copy runs, and a copy that is replaced ends at its next checkpoint without writing`() {
    val hold = CompletableDeferred<Unit>()
    val old = backfill(twoMonthsHeld(hold), owner = "old-copy")
    old.start()
    runBlocking { withTimeout(10_000) { heldClient.entered.await() } }

    val replacement = backfill(twoMonths(), owner = "new-copy")
    replacement.start()
    awaitState(replacement, RunState.DONE, RunState.FAILED)
    assertEquals("new-copy", runRow()?.cursorValue)

    hold.complete(Unit)
    val ended = awaitState(old, RunState.IDLE)

    assertTrue(ended.message.orEmpty().contains("Another copy"), ended.message)
    assertEquals("DONE", runRow()?.status, "the old copy must not overwrite the new copy's state")
    assertEquals("new-copy", runRow()?.cursorValue)
  }

  @Test
  fun `stop from a copy that is not running releases the run and keeps its counters`() {
    val hold = CompletableDeferred<Unit>()
    val running = backfill(twoMonthsHeld(hold), owner = "running-copy")
    running.start()
    runBlocking { withTimeout(10_000) { heldClient.entered.await() } }
    runBlocking {
      repo.saveState(BackfillState("run", "RUNNING", "running-copy", 7, null, 130, LocalDate.of(2014, 2, 20), null, null, null, null))
    }

    runBlocking { backfill(twoMonths(), owner = "other-copy").stop() }

    val row = runRow()!!
    assertEquals("IDLE", row.status)
    assertEquals(null, row.cursorValue)
    assertEquals(130, row.requestsToday, "today's call count must survive a stop")
    hold.complete(Unit)
    awaitState(running, RunState.IDLE)
  }

  @Test
  fun `restarting keeps the day's call count`() {
    runBlocking {
      repo.saveState(BackfillState("run", "DONE", "x", 0, null, 500, LocalDate.of(2014, 2, 20), null, null, null, null))
    }
    val b = backfill(twoMonths())

    b.start(restart = true)
    val done = awaitState(b, RunState.DONE, RunState.FAILED)

    assertTrue(done.requestsToday >= 500, "requests today: ${done.requestsToday}")
  }

  @Test
  fun `the plan and the first step are visible before any step finishes`() {
    val hold = CompletableDeferred<Unit>()
    val b = backfill(twoMonthsHeld(hold))

    b.start()
    runBlocking { withTimeout(10_000) { heldClient.entered.await() } }
    val p = b.status()

    assertEquals(3, p.plan.size)
    assertEquals(3, p.stageCount)
    assertEquals(1, p.stageIndex)
    assertTrue(p.stageTitle.orEmpty().isNotBlank())
    assertEquals("monthly searches", p.stageUnit)
    assertTrue(p.activity.orEmpty().startsWith("Searching"), p.activity)
    assertEquals(null, p.limit)
    assertEquals(2, p.stageTotal)
    assertTrue(p.plan.all { it.title.isNotBlank() })
    assertTrue(p.etaOverallSeconds != null && p.etaOverallSeconds!! > 0, "overall eta ${p.etaOverallSeconds}")
    assertTrue(p.paceCallsPerMinute > 0)

    hold.complete(Unit)
    awaitState(b, RunState.DONE)
  }

  @Test
  fun `starting reports a running snapshot at once, with the trial limit if one was given`() {
    val hold = CompletableDeferred<Unit>()
    val b = backfill(twoMonthsHeld(hold))

    b.start(limit = 5)
    val right = b.status()

    assertEquals(RunState.RUNNING, right.state)
    assertEquals(5, right.limit)
    assertEquals("Starting", right.activity)
    runBlocking { b.stop() }
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
    assertEquals("IDLE", runRow()?.status)
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
  fun `eta never assumes more calls in a day than the pace allows`() {
    // At 6 s per call a day holds only 14,400 calls even though the budget says 25,000, so 28,800
    // calls take two days of work (172,800 s), not the 3.6 days the budget alone would suggest.
    val eta = etaSeconds(28_800, 0, 25_000, 6.0, 1_000)
    assertTrue(eta in 172_800L..172_810L, "eta $eta")
    // And with the budget the limit, the day ends early and the rest of it is waited out.
    assertEquals(100 * 3 + (86_400 - 300) + 100 * 3, etaSeconds(200, 0, 100, 3.0, 86_400))
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

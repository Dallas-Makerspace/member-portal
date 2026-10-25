package org.dallasmakerspace.bambuddy.notifications

import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.dallasmakerspace.bambuddy.notifications.db.BambuScheduleRepository
import org.dallasmakerspace.core.LoggerFactory
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.slf4j.Logger

class BambuNotificationSchedulerTest {

  private val start: LocalDateTime = LocalDateTime.of(2026, 10, 24, 12, 0)
  private var clock = start

  private lateinit var sweeper: BambuNotificationSweeper
  private lateinit var scheduleRepository: BambuScheduleRepository
  private lateinit var wakeSignal: BambuWakeSignal
  private lateinit var logger: Logger
  private lateinit var scheduler: BambuNotificationScheduler

  @Before
  fun setUp() {
    clock = start
    logger = mock()
    val loggerFactory = mock<LoggerFactory>()
    whenever(loggerFactory.create(any<Class<*>>())).thenReturn(logger)
    sweeper = mock()
    scheduleRepository = mock()
    wakeSignal = BambuWakeSignal()
    runBlocking<Unit> {
      whenever(sweeper.featureInAnyRollout()).thenReturn(true)
      whenever(sweeper.reconcile()).thenReturn(0)
      whenever(sweeper.processDueWatches()).thenReturn(SweepResult("ok"))
      whenever(sweeper.releaseDueOfflineAlerts()).thenReturn(SweepResult("ok"))
      whenever(sweeper.purgeTombstones()).thenReturn(SweepResult("ok"))
      whenever(scheduleRepository.nextDue()).thenReturn(null)
    }
    scheduler = BambuNotificationScheduler(sweeper, scheduleRepository, wakeSignal, loggerFactory)
    scheduler.now = { clock }
  }

  private fun stubFailingPass() = runBlocking<Unit> {
    whenever(sweeper.processDueWatches()).thenReturn(SweepResult("failed=1", failed = 1))
  }

  // ---- next-due computation ------------------------------------------------------------------

  @Test
  fun `wait is the time to the earliest of the next stored deadline and the purge`() {
    val purge = start.plusDays(1)
    val wait = BambuNotificationScheduler.computeWaitMillis(start, start.plusMinutes(90), purge, 0)
    assertEquals(90 * 60_000L, wait)
    assertEquals(
        24 * 3_600_000L, BambuNotificationScheduler.computeWaitMillis(start, null, purge, 0))
    assertEquals(
        60_000L,
        BambuNotificationScheduler.computeWaitMillis(start, start.plusDays(3), start.plusMinutes(1), 0))
  }

  @Test
  fun `a deadline already in the past means run immediately, but never under the backoff`() {
    val purge = start.plusDays(1)
    assertEquals(
        0L, BambuNotificationScheduler.computeWaitMillis(start, start.minusMinutes(5), purge, 0))
    assertEquals(
        60_000L,
        BambuNotificationScheduler.computeWaitMillis(start, start.minusMinutes(5), purge, 60_000))
  }

  @Test
  fun `backoff starts at a minute, doubles, and is capped`() {
    assertEquals(0L, BambuNotificationScheduler.backoffMillis(0))
    assertEquals(60_000L, BambuNotificationScheduler.backoffMillis(1))
    assertEquals(120_000L, BambuNotificationScheduler.backoffMillis(2))
    assertEquals(240_000L, BambuNotificationScheduler.backoffMillis(3))
    assertEquals(15 * 60_000L, BambuNotificationScheduler.backoffMillis(30))
  }

  // ---- steps ---------------------------------------------------------------------------------

  @Test
  fun `a pass sleeps until the stored deadline`() = runBlocking<Unit> {
    whenever(scheduleRepository.nextDue()).thenReturn(start.plusMinutes(45))

    assertEquals(45 * 60_000L, scheduler.step())

    verify(sweeper).reconcile()
    verify(sweeper).processDueWatches()
    verify(sweeper).releaseDueOfflineAlerts()
    verify(sweeper).purgeTombstones()
  }

  @Test
  fun `nothing stored sleeps until the daily purge, which then runs once a day`() = runBlocking<Unit> {
    assertEquals(24 * 3_600_000L, scheduler.step())

    clock = start.plusHours(23)
    scheduler.step()
    verify(sweeper, times(1)).purgeTombstones()

    clock = start.plusHours(25)
    scheduler.step()
    verify(sweeper, times(2)).purgeTombstones()
    // Reconciliation is a startup / after-failure concern, not a per-pass one.
    verify(sweeper, times(1)).reconcile()
  }

  @Test
  fun `with the feature fully off nothing is queried and the loop waits for a wake`() =
      runBlocking<Unit> {
        whenever(sweeper.featureInAnyRollout()).thenReturn(false)

        assertNull(scheduler.step())

        verify(scheduleRepository, never()).nextDue()
        verify(sweeper, never()).reconcile()
        verify(sweeper, never()).processDueWatches()
        verify(sweeper, never()).releaseDueOfflineAlerts()
        verify(sweeper, never()).purgeTombstones()
      }

  @Test
  fun `a failed pass backs off and reconciles, growing on repeat failures, and resets on success`() =
      runBlocking<Unit> {
        whenever(scheduleRepository.nextDue()).thenReturn(start.minusMinutes(10))
        stubFailingPass()

        assertEquals(60_000L, scheduler.step())
        assertEquals(120_000L, scheduler.step())
        // Startup reconcile + one after each failed pass.
        verify(sweeper, times(3)).reconcile()

        whenever(sweeper.processDueWatches()).thenReturn(SweepResult("ok"))
        whenever(scheduleRepository.nextDue()).thenReturn(start.plusMinutes(30))
        assertEquals(30 * 60_000L, scheduler.step())
      }

  @Test
  fun `a deadline that never clears without failures still backs off instead of spinning`() =
      runBlocking<Unit> {
        whenever(scheduleRepository.nextDue()).thenReturn(start.minusMinutes(1))

        repeat(5) { assertEquals(0L, scheduler.step()) }
        assertEquals(60_000L, scheduler.step())
      }

  @Test
  fun `a startup read failure logs one warning and waits for a wake without retrying`() =
      runBlocking<Unit> {
        whenever(scheduleRepository.nextDue()).thenThrow(RuntimeException("table missing"))

        assertNull(scheduler.step())

        verify(logger, times(1)).warn(any<String>())
        verify(sweeper, never()).processDueWatches()
        verify(sweeper, never()).releaseDueOfflineAlerts()
      }

  @Test
  fun `a failure after a good start backs off and retries`() = runBlocking<Unit> {
    scheduler.step()
    whenever(scheduleRepository.nextDue()).thenThrow(RuntimeException("db blip"))

    assertEquals(60_000L, scheduler.step())
    assertEquals(120_000L, scheduler.step())
  }

  // ---- wake ----------------------------------------------------------------------------------

  @Test
  fun `a wake makes the idle loop re-evaluate and pick up the new work`() = runBlocking<Unit> {
    val sawOff = CompletableDeferred<Unit>()
    val ran = CompletableDeferred<Unit>()
    var enabled = false
    whenever(sweeper.featureInAnyRollout()).thenAnswer {
      if (!enabled) sawOff.complete(Unit)
      enabled
    }
    whenever(sweeper.processDueWatches()).thenAnswer {
      ran.complete(Unit)
      SweepResult("ok")
    }

    val loop = launch(Dispatchers.Default) { scheduler.run() }
    withTimeout(5_000) { sawOff.await() }
    verify(sweeper, never()).processDueWatches()

    enabled = true
    scheduler.wake()
    withTimeout(5_000) { ran.await() }
    loop.cancelAndJoin()
  }
}

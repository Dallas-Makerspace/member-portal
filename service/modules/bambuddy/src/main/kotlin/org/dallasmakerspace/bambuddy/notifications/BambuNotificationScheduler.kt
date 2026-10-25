package org.dallasmakerspace.bambuddy.notifications

import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import org.dallasmakerspace.bambuddy.notifications.db.BambuScheduleRepository
import org.dallasmakerspace.core.LoggerFactory

private const val BACKOFF_BASE_MILLIS = 60_000L
private const val BACKOFF_MAX_MILLIS = 15 * 60_000L
private const val MAX_BACK_TO_BACK_PASSES = 5
private const val MAX_BACKOFF_SHIFT = 10

/**
 * Drives [BambuNotificationSweeper] without polling: after each pass it reads the next stored
 * deadline (earliest ARMED expiry / PENDING offline release, or the daily purge) and sleeps until
 * then or until [wake] says a new, possibly earlier deadline exists. While the feature is fully
 * off nothing is queried and the loop only waits for a wake. Concurrent instances are safe: the
 * sweeper's atomic claims decide who handles each item.
 */
@Singleton
class BambuNotificationScheduler
@Inject
constructor(
    private val sweeper: BambuNotificationSweeper,
    private val scheduleRepository: BambuScheduleRepository,
    private val wakeSignal: BambuWakeSignal,
    loggerFactory: LoggerFactory,
) {
  private val log = loggerFactory.create(javaClass)
  private val utcZone = ZoneId.of("UTC")

  internal var now: () -> LocalDateTime = { LocalDateTime.now(utcZone) }

  private var startupDone = false
  private var failureStreak = 0
  private var backToBackPasses = 0
  private var nextPurgeAt: LocalDateTime? = null

  fun wake() = wakeSignal.wake()

  /** Runs until cancelled. */
  suspend fun run() {
    while (currentCoroutineContext().isActive) {
      wakeSignal.await(step())
    }
  }

  /**
   * One scheduling iteration: run the passes, then return how long to sleep in milliseconds
   * (null = until woken).
   */
  @Suppress("ReturnCount")
  internal suspend fun step(): Long? {
    try {
      if (!sweeper.featureInAnyRollout()) {
        failureStreak = 0
        backToBackPasses = 0
        return null
      }
      if (!startupDone) {
        // Probe first: a missing table (migration not applied) fails here, before any pass.
        scheduleRepository.nextDue()
        sweeper.reconcile()
        startupDone = true
      }
      val failed = sweeper.processDueWatches().failed + sweeper.releaseDueOfflineAlerts().failed
      purgeIfDue()
      if (failed > 0) {
        failureStreak++
        sweeper.reconcile()
      }
      val nextDue = scheduleRepository.nextDue()
      if (failed == 0) failureStreak = 0
      return nextWait(nextDue)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (!startupDone) {
        log.warn("Bambu scheduler startup read failed, waiting for the next event: ${e.message}")
        return null
      }
      failureStreak++
      log.warn("Bambu scheduler pass failed, retrying after backoff: ${e.message}")
      return backoffMillis(failureStreak)
    }
  }

  private suspend fun purgeIfDue() {
    val current = now()
    val due = nextPurgeAt
    if (due == null || !current.isBefore(due)) {
      sweeper.purgeTombstones()
      nextPurgeAt = current.plusDays(1)
    }
  }

  private fun nextWait(nextDue: LocalDateTime?): Long {
    val wait =
        computeWaitMillis(now(), nextDue, checkNotNull(nextPurgeAt), backoffMillis(failureStreak))
    // A deadline that stays in the past without any failure would spin; treat it as one.
    if (wait == 0L && ++backToBackPasses > MAX_BACK_TO_BACK_PASSES) {
      log.warn("Bambu scheduler: due work is not clearing, backing off")
      backToBackPasses = 0
      failureStreak++
      return backoffMillis(failureStreak)
    }
    if (wait > 0L) backToBackPasses = 0
    return wait
  }

  companion object {
    /** Milliseconds to sleep: until the earliest deadline (past = 0), but never under [backoff]. */
    internal fun computeWaitMillis(
        now: LocalDateTime,
        nextDue: LocalDateTime?,
        nextPurgeAt: LocalDateTime,
        backoff: Long,
    ): Long {
      val target = listOfNotNull(nextDue, nextPurgeAt).min()
      val untilTarget = Duration.between(now, target).toMillis().coerceAtLeast(0L)
      return maxOf(untilTarget, backoff)
    }

    /** 0 with no failures, then 60 s doubling per consecutive failure up to 15 min. */
    internal fun backoffMillis(failureStreak: Int): Long {
      if (failureStreak <= 0) return 0L
      val shift = (failureStreak - 1).coerceAtMost(MAX_BACKOFF_SHIFT)
      return (BACKOFF_BASE_MILLIS shl shift).coerceAtMost(BACKOFF_MAX_MILLIS)
    }
  }
}

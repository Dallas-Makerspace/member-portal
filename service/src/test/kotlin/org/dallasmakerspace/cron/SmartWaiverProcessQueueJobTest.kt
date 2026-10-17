package org.dallasmakerspace.cron

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.config.ConfigOverrideService
import org.dallasmakerspace.config.ConfigRegistry
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.smartwaiver.ISmartwaiverApiClient
import org.dallasmakerspace.smartwaiver.MirrorOutcome
import org.dallasmakerspace.smartwaiver.QueueEntry
import org.dallasmakerspace.smartwaiver.SmartWaiverCheckinSync
import org.dallasmakerspace.smartwaiver.SmartWaiverMirrorRepository
import org.dallasmakerspace.smartwaiver.SmartWaiverMirrorService
import org.dallasmakerspace.smartwaiver.SmartWaiverRepository
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class SmartWaiverProcessQueueJobTest {
  private val client = mock<ISmartwaiverApiClient>()
  private val repository = mock<SmartWaiverRepository>()
  private val mirrorRepository = mock<SmartWaiverMirrorRepository>()
  private val mirrorService = mock<SmartWaiverMirrorService>()
  private val checkinSync = mock<SmartWaiverCheckinSync>()
  private val config = mock<ConfigOverrideService>()
  private val job =
      SmartWaiverProcessQueueJob(
          client, repository, mirrorRepository, mirrorService, checkinSync, config, LoggerFactory())

  private fun mirrorFlag(on: Boolean) {
    runBlocking { whenever(config.get(ConfigRegistry.SMARTWAIVER_MIRROR_ENABLED)).thenReturn(on) }
  }

  @Test
  fun `a run requested mid-run does not overlap but triggers a follow-up pass`() {
    mirrorFlag(false)
    runBlocking {
      val firstPassStarted = CompletableDeferred<Unit>()
      val releaseFirstPass = CompletableDeferred<Unit>()
      var passes = 0
      whenever(repository.getUnprocessedQueueEntries()).thenAnswer {
        passes++
        if (passes == 1) {
          firstPassStarted.complete(Unit)
          runBlocking { releaseFirstPass.await() }
        }
        emptyList<Triple<Int, String, String>>()
      }

      val first = async(Dispatchers.Default) { job.run() }
      firstPassStarted.await()

      val second = job.run()
      assertTrue(second.startsWith("already running"), second)

      releaseFirstPass.complete(Unit)
      first.await()

      verify(repository, times(2)).getUnprocessedQueueEntries()
      verify(client, never()).getWaiver(any())
    }
  }

  @Test
  fun `a later run starts normally once the previous one finished`() {
    mirrorFlag(false)
    runBlocking {
      whenever(repository.getUnprocessedQueueEntries()).thenReturn(emptyList())

      job.run()
      val again = job.run()

      assertTrue(again.startsWith("total=0"), again)
      verify(repository, times(2)).getUnprocessedQueueEntries()
    }
  }

  @Test
  fun `with the mirror off, the legacy path runs and the mirror is untouched`() {
    mirrorFlag(false)
    runBlocking {
      whenever(repository.getUnprocessedQueueEntries()).thenReturn(emptyList())

      job.run()

      verify(mirrorRepository, never()).retryableQueueEntries(any(), any())
    }
  }

  @Test
  fun `with the mirror on, waivers are mirrored and marked processed, failures are counted`() {
    mirrorFlag(true)
    runBlocking {
      whenever(mirrorRepository.retryableQueueEntries(any(), any()))
          .thenReturn(listOf(QueueEntry(1, "fakewaiver1", EVENT_NEW_WAIVER), QueueEntry(2, "fakewaiver2", EVENT_NEW_WAIVER)))
      whenever(mirrorService.mirrorWaiver("fakewaiver1")).thenReturn(MirrorOutcome("fakewaiver1", 3, "COMPLETE"))
      whenever(mirrorService.mirrorWaiver("fakewaiver2")).thenThrow(IllegalStateException("boom"))

      val summary = job.run()

      assertTrue(summary.contains("processed=1") && summary.contains("failed=1"), summary)
      verify(mirrorRepository).markQueueProcessed(listOf(1))
      verify(mirrorRepository).recordQueueFailure(2, "boom")
      verify(mirrorRepository, never()).markQueueProcessed(listOf(2))
      verify(repository, never()).getUnprocessedQueueEntries()
    }
  }

  @Test
  fun `check-in events trigger one catch-up sync for all of them`() {
    mirrorFlag(true)
    runBlocking {
      whenever(mirrorRepository.retryableQueueEntries(any(), any()))
          .thenReturn(listOf(QueueEntry(5, "checkin-sync", EVENT_CHECKIN), QueueEntry(6, "checkin-sync", EVENT_CHECKIN)))
      whenever(checkinSync.syncRecent()).thenReturn(4)

      job.run()

      verify(checkinSync, times(1)).syncRecent()
      verify(mirrorRepository).markQueueProcessed(listOf(5, 6))
      verify(mirrorService, never()).mirrorWaiver(any())
    }
  }

  @Test
  fun `a failed check-in sync records a failure against each check-in event`() {
    mirrorFlag(true)
    runBlocking {
      whenever(mirrorRepository.retryableQueueEntries(any(), any()))
          .thenReturn(listOf(QueueEntry(5, "checkin-sync", EVENT_CHECKIN)))
      whenever(checkinSync.syncRecent()).thenThrow(IllegalStateException("api down"))

      job.run()

      verify(mirrorRepository).recordQueueFailure(5, "api down")
      verify(mirrorRepository, never()).markQueueProcessed(anyOrNull())
    }
  }
}

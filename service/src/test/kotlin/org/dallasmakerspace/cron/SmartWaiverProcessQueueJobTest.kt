package org.dallasmakerspace.cron

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.smartwaiver.ISmartwaiverApiClient
import org.dallasmakerspace.smartwaiver.SmartWaiverRepository
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class SmartWaiverProcessQueueJobTest {
  private val client = mock<ISmartwaiverApiClient>()
  private val repository = mock<SmartWaiverRepository>()
  private val job = SmartWaiverProcessQueueJob(client, repository, LoggerFactory())

  @Test
  fun `a run requested mid-run does not overlap but triggers a follow-up pass`() = runBlocking {
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
    verify(client, times(0)).getWaiver(any())
    Unit
  }

  @Test
  fun `a later run starts normally once the previous one finished`() = runBlocking {
    whenever(repository.getUnprocessedQueueEntries()).thenReturn(emptyList())

    job.run()
    val again = job.run()

    assertTrue(again.startsWith("total=0"), again)
    verify(repository, times(2)).getUnprocessedQueueEntries()
    Unit
  }
}

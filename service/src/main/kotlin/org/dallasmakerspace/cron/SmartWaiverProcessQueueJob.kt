package org.dallasmakerspace.cron

import dagger.Reusable
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.smartwaiver.ISmartwaiverApiClient
import org.dallasmakerspace.smartwaiver.SmartWaiverRepository

private const val EVENT_NEW_WAIVER = "new-waiver"

// File-level because @Reusable doesn't guarantee a single job instance.
private val running = AtomicBoolean(false)
private val rerunRequested = AtomicBoolean(false)

@Reusable
class SmartWaiverProcessQueueJob
@Inject
constructor(
    private val smartwaiverApiClient: ISmartwaiverApiClient,
    private val smartWaiverRepository: SmartWaiverRepository,
    loggerFactory: LoggerFactory,
) {
  private val log = loggerFactory.create(javaClass)

  /**
   * Runs one queue pass at a time. A caller arriving mid-run doesn't start a second pass; it asks
   * the running one to go around again so entries queued after that pass started aren't missed.
   */
  suspend fun run(): String {
    if (!running.compareAndSet(false, true)) {
      rerunRequested.set(true)
      return "already running; a follow-up pass is queued"
    }
    val result: String
    try {
      var summary: String
      do {
        rerunRequested.set(false)
        summary = runOnce()
      } while (rerunRequested.get())
      result = summary
    } finally {
      running.set(false)
    }
    // A request that landed after the last check above but before running was cleared.
    return if (rerunRequested.get()) run() else result
  }

  @Suppress("TooGenericExceptionCaught")
  private suspend fun runOnce(): String {
    val entries = smartWaiverRepository.getUnprocessedQueueEntries()
    log.info("Found ${entries.size} unprocessed queue entries")

    var processed = 0
    var failed = 0

    for ((queueId, uniqueId, event) in entries) {
      if (event != EVENT_NEW_WAIVER) {
        log.warn("Skipping unexpected event type '$event' for waiver $uniqueId")
        continue
      }
      try {
        val t0 = System.currentTimeMillis()
        val waiver = smartwaiverApiClient.getWaiver(uniqueId)
        log.info("Fetched waiver $uniqueId (${System.currentTimeMillis() - t0}ms)")

        smartWaiverRepository.insertWaiverAndMarkProcessed(waiver, queueId)
        log.info("Processed waiver $uniqueId")
        processed++
      } catch (e: Exception) {
        log.error("Failed to process waiver $uniqueId: ${e.message}")
        failed++
      }
    }

    return "total=${entries.size}\nprocessed=$processed\nfailed=$failed"
  }
}

package org.dallasmakerspace.cron

import dagger.Reusable
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import org.dallasmakerspace.config.ConfigOverrideService
import org.dallasmakerspace.config.ConfigRegistry
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.smartwaiver.ISmartwaiverApiClient
import org.dallasmakerspace.smartwaiver.SmartWaiverCheckinSync
import org.dallasmakerspace.smartwaiver.SmartWaiverMirrorRepository
import org.dallasmakerspace.smartwaiver.SmartWaiverMirrorService
import org.dallasmakerspace.smartwaiver.SmartWaiverRepository
import org.dallasmakerspace.smartwaiver.safeMessage

const val EVENT_NEW_WAIVER = "new-waiver"
const val EVENT_CHECKIN = "new-checkin"

private const val MAX_ATTEMPTS = 5
private const val QUEUE_LOOKBACK_DAYS = 30L

// File-level because @Reusable doesn't guarantee a single job instance.
private val running = AtomicBoolean(false)
private val rerunRequested = AtomicBoolean(false)

@Reusable
class SmartWaiverProcessQueueJob
@Inject
constructor(
    private val smartwaiverApiClient: ISmartwaiverApiClient,
    private val smartWaiverRepository: SmartWaiverRepository,
    private val mirrorRepository: SmartWaiverMirrorRepository,
    private val mirrorService: SmartWaiverMirrorService,
    private val checkinSync: SmartWaiverCheckinSync,
    private val configOverrideService: ConfigOverrideService,
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
        summary = if (mirrorEnabled()) runMirrorPass() else runLegacyPass()
      } while (rerunRequested.get())
      result = summary
    } finally {
      running.set(false)
    }
    // A request that landed after the last check above but before running was cleared.
    return if (rerunRequested.get()) run() else result
  }

  @Suppress("TooGenericExceptionCaught")
  private suspend fun mirrorEnabled(): Boolean =
      try {
        configOverrideService.get(ConfigRegistry.SMARTWAIVER_MIRROR_ENABLED)
      } catch (e: Exception) {
        log.warn("Could not read smartwaiver.mirror.enabled, using the legacy path: ${e.message}")
        false
      }

  /** Full mirror: waiver, participants, kiosk photos, plus a check-in catch-up for check-in events. */
  @Suppress("TooGenericExceptionCaught")
  private suspend fun runMirrorPass(): String {
    val entries = mirrorRepository.retryableQueueEntries(MAX_ATTEMPTS, QUEUE_LOOKBACK_DAYS)
    log.info("Found ${entries.size} unprocessed queue entries (mirror)")

    var processed = 0
    var failed = 0
    val checkinEntryIds = mutableListOf<Int>()

    for (entry in entries) {
      when (entry.event) {
        EVENT_NEW_WAIVER ->
            try {
              val outcome = mirrorService.mirrorWaiver(entry.uniqueId)
              mirrorRepository.markQueueProcessed(listOf(entry.id))
              log.info("Mirrored waiver ${entry.uniqueId} (${outcome.photos} photos, ${outcome.photosStatus})")
              processed++
            } catch (e: Exception) {
              log.error("Failed to mirror waiver ${entry.uniqueId}: ${e.safeMessage()}")
              mirrorRepository.recordQueueFailure(entry.id, e.safeMessage())
              failed++
            }
        EVENT_CHECKIN -> checkinEntryIds += entry.id
        else -> log.warn("Skipping unexpected event type '${entry.event}' for ${entry.uniqueId}")
      }
    }

    if (checkinEntryIds.isNotEmpty()) {
      try {
        val added = checkinSync.syncRecent()
        mirrorRepository.markQueueProcessed(checkinEntryIds)
        log.info("Check-in sync added $added check-ins")
        processed += checkinEntryIds.size
      } catch (e: Exception) {
        log.error("Check-in sync failed: ${e.safeMessage()}")
        checkinEntryIds.forEach { mirrorRepository.recordQueueFailure(it, e.safeMessage()) }
        failed += checkinEntryIds.size
      }
    }
    return "total=${entries.size}\nprocessed=$processed\nfailed=$failed"
  }

  @Suppress("TooGenericExceptionCaught")
  private suspend fun runLegacyPass(): String {
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
        log.error("Failed to process waiver $uniqueId: ${e.safeMessage()}")
        failed++
      }
    }

    return "total=${entries.size}\nprocessed=$processed\nfailed=$failed"
  }
}

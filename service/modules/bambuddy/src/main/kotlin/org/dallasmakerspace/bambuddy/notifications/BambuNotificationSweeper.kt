package org.dallasmakerspace.bambuddy.notifications

import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.dallasmakerspace.bambuddy.BambuPrinterStatus
import org.dallasmakerspace.bambuddy.BambuQueueItem
import org.dallasmakerspace.bambuddy.IBambuddyApiClient
import org.dallasmakerspace.bambuddy.bambuEffectiveState
import org.dallasmakerspace.bambuddy.notifications.db.BambuCommitteeSubscriptionsRepository
import org.dallasmakerspace.bambuddy.notifications.db.EphemeralNotificationsRepository
import org.dallasmakerspace.bambuddy.notifications.db.PendingOfflineAlertsRepository
import org.dallasmakerspace.config.ConfigOverrideService
import org.dallasmakerspace.config.ConfigRegistry
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.notifications.Channel
import org.dallasmakerspace.notifications.NotificationService
import org.dallasmakerspace.notifications.templates.BambuPrinterOfflineData
import org.dallasmakerspace.notifications.templates.bambuStatusUrl
import org.dallasmakerspace.notifications.topics.BambuCommitteeAlertsTopic

private const val BATCH_LIMIT = 50

/** One pass's log summary plus how many items it failed on (drives the scheduler's backoff). */
data class SweepResult(val summary: String, val failed: Int = 0)

/**
 * Background passes for the Bambu watch domain (design §10), driven by [BambuNotificationScheduler]:
 * due-watch expiry checks, offline-hold release, reconciliation and tombstone purge. Each pass
 * returns a [SweepResult] for logging and backoff. When the feature is fully off, every pass
 * no-ops.
 */
@Singleton
class BambuNotificationSweeper
@Inject
constructor(
    private val watchRepository: EphemeralNotificationsRepository,
    private val watchService: EphemeralNotificationService,
    private val pendingOfflineRepository: PendingOfflineAlertsRepository,
    private val committeeSubscriptionsRepository: BambuCommitteeSubscriptionsRepository,
    private val apiClient: IBambuddyApiClient,
    private val notificationService: NotificationService,
    private val configOverrideService: ConfigOverrideService,
    loggerFactory: LoggerFactory,
) {
  private val log = loggerFactory.create(javaClass)
  private val json = Json { encodeDefaults = true }
  private val utcZone = ZoneId.of("UTC")

  /**
   * Recovers terminal tombstones whose notice was never created (crash between the claim and the
   * create). Returns how many were recovered.
   */
  suspend fun reconcile(): Int =
      try {
        watchService.reconcileTerminalWatches(BATCH_LIMIT)
      } catch (e: Exception) {
        log.error("Bambu watch reconciliation failed: ${e.message}", e)
        0
      }

  suspend fun processDueWatches(): SweepResult {
    if (!featureInAnyRollout()) return SweepResult("processed=0\ndisabled=true")
    val now = LocalDateTime.now(utcZone)
    val due = watchRepository.findDueArmed(now, BATCH_LIMIT)
    // One shared fetch per pass (not per watch): the queue once, status once per distinct
    // printer — a backlog of watches on a few printers costs a handful of BamBuddy reads.
    val prefetch = if (due.isEmpty()) null else fetchExpiryPrefetch(due)
    var fired = 0
    var rearmed = 0
    var retargeted = 0
    var expired = 0
    var claimLost = 0
    var failed = 0
    for (watch in due) {
      try {
        when (watchService.processExpiredWatch(watch, prefetch)) {
          ExpiryOutcome.FIRED -> fired++
          ExpiryOutcome.OVERDUE_REARMED -> rearmed++
          ExpiryOutcome.RETARGETED -> retargeted++
          ExpiryOutcome.EXPIRED -> expired++
          ExpiryOutcome.CLAIM_LOST -> claimLost++
        }
      } catch (e: Exception) {
        // One bad watch never wedges the pass; the next pass retries it (dedupe keys make that
        // safe).
        failed++
        log.error("Expiry processing failed for watch=${watch.id}: ${e.message}", e)
      }
    }
    if (due.isNotEmpty()) {
      log.info(
          "bambu sweeper watches: due=${due.size} fired=$fired rearmed=$rearmed retargeted=$retargeted expired=$expired claimLost=$claimLost failed=$failed")
    }
    return SweepResult(
        "due=${due.size}\nfired=$fired\nrearmed=$rearmed\nretargeted=$retargeted\nexpired=$expired\nclaimLost=$claimLost\nfailed=$failed",
        failed)
  }

  /** Fetches the queue once and each distinct due printer's status once, failures as nulls. */
  private suspend fun fetchExpiryPrefetch(due: List<EphemeralNotification>): ExpiryPrefetch {
    val queue: List<BambuQueueItem>? =
        try {
          apiClient.getQueue()
        } catch (e: Exception) {
          log.warn("Expiry prefetch: getQueue failed: ${e.message}")
          null
        }
    val statuses = mutableMapOf<Int, BambuPrinterStatus?>()
    for (printerId in due.map { it.printerId }.distinct()) {
      statuses[printerId] =
          try {
            apiClient.getPrinterStatus(printerId)
          } catch (e: Exception) {
            log.warn("Expiry prefetch: getPrinterStatus failed for printer $printerId: ${e.message}")
            null
          }
    }
    return ExpiryPrefetch(queue, statuses)
  }

  suspend fun releaseDueOfflineAlerts(): SweepResult {
    if (!featureInAnyRollout()) return SweepResult("processed=0\ndisabled=true")
    val now = LocalDateTime.now(utcZone)
    val due = pendingOfflineRepository.findDuePending(now, BATCH_LIMIT)
    var sent = 0
    var suppressed = 0
    var failed = 0
    for (alert in due) {
      try {
        if (releaseOne(alert, now)) sent++ else suppressed++
      } catch (e: Exception) {
        failed++
        log.error("Offline alert release failed for episode=${alert.id}: ${e.message}", e)
      }
    }
    if (due.isNotEmpty()) {
      log.info("bambu sweeper offline: due=${due.size} sent=$sent suppressed=$suppressed failed=$failed")
    }
    return SweepResult("due=${due.size}\nsent=$sent\nsuppressed=$suppressed\nfailed=$failed", failed)
  }

  /**
   * Claims the episode (PENDING → PROCESSING) BEFORE the re-check / fan-out, so under
   * overlapping sweeper instances only the claim winner proceeds — a SUPPRESSED episode can
   * never email subscribers from a second instance that re-checked a stale copy. Then: back
   * online → SUPPRESSED (no notification is ever created); still offline — or BamBuddy
   * cannot confirm recovery (status null / call throws both fold to OFFLINE, as in
   * BambuddyStatusService) → fan out to topic subscribers, then SENT. A failed attempt
   * returns the claim to PENDING for the next pass. Returns true when the alert was sent.
   */
  private suspend fun releaseOne(alert: PendingOfflineAlert, now: LocalDateTime): Boolean {
    if (!pendingOfflineRepository.claimProcessing(alert.id)) {
      log.info("offline release claim lost episode=${alert.id} (another sweeper is releasing it)")
      return false
    }
    try {
      val state =
          try {
            bambuEffectiveState(apiClient.getPrinterStatus(alert.printerId))
          } catch (e: Exception) {
            log.warn("Offline re-check failed for printer ${alert.printerId}: ${e.message}")
            "OFFLINE"
          }
      if (state != "OFFLINE") {
        val won = pendingOfflineRepository.claimSuppressed(alert.id, now)
        if (won) log.info("offline suppressed (back online) printer=${alert.printerId} episode=${alert.id}")
        return false
      }
      val subscribers = committeeSubscriptionsRepository.getSubscribers()
      val payload =
          json.encodeToString(
              BambuPrinterOfflineData(
                  printerName = alert.printerName,
                  offlineSinceText = formatCentralTime(alert.offlineSince),
                  statusPageUrl = bambuStatusUrl(),
                  holdMinutes = Duration.between(alert.offlineSince, alert.releaseAt).toMinutes().toInt(),
              ))
      val result =
          notificationService.createForMembers(
              memberUsernames = subscribers,
              topic = BambuCommitteeAlertsTopic,
              templateName = "bambu-printer-offline",
              payload = payload,
              channel = Channel.EMAIL,
              dedupeKey = { username -> "bambu:offline:${alert.printerId}:${alert.id}:$username" },
              consentOverride = false,
          )
      val won = pendingOfflineRepository.claimSent(alert.id, now, result.created)
      if (won) {
        log.info(
            "offline released printer=${alert.printerId} episode=${alert.id} subscribers=${subscribers.size} created=${result.created}")
      }
      return true
    } catch (e: Exception) {
      // Give the claim back so the next pass retries this episode instead of stranding it
      // in PROCESSING. (Per-member dedupe keys make a retried fan-out harmless.)
      try {
        pendingOfflineRepository.releaseProcessingClaim(alert.id)
      } catch (releaseError: Exception) {
        log.error("Failed to release PROCESSING claim for episode=${alert.id}: ${releaseError.message}", releaseError)
      }
      throw e
    }
  }

  suspend fun purgeTombstones(): SweepResult {
    if (!featureInAnyRollout()) return SweepResult("purged=0\ndisabled=true")
    val retentionDays =
        configOverrideService.get(ConfigRegistry.BAMBU_NOTIFICATIONS_TOMBSTONE_RETENTION_DAYS)
    val cutoff = LocalDateTime.now(utcZone).minusDays(retentionDays.coerceAtLeast(0).toLong())
    val watches = watchRepository.purgeTombstones(cutoff)
    val alerts = pendingOfflineRepository.purgeResolved(cutoff)
    if (watches > 0 || alerts > 0) {
      log.info("bambu sweeper purge: watches=$watches offlineAlerts=$alerts")
    }
    return SweepResult("watches=$watches\nofflineAlerts=$alerts")
  }

  suspend fun featureInAnyRollout(): Boolean =
      configOverrideService.get(ConfigRegistry.BAMBU_NOTIFICATIONS_ENABLED).inAnyRollout()
}

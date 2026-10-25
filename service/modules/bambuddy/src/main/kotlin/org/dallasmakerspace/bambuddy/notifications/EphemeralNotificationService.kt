package org.dallasmakerspace.bambuddy.notifications

import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.dallasmakerspace.bambuddy.BAMBU_PRINTING_STATES
import org.dallasmakerspace.bambuddy.BambuPrinterStatus
import org.dallasmakerspace.bambuddy.BambuQueueItem
import org.dallasmakerspace.bambuddy.BambuddyApiException
import org.dallasmakerspace.bambuddy.IBambuddyApiClient
import org.dallasmakerspace.bambuddy.bambuEffectiveState
import org.dallasmakerspace.bambuddy.notifications.db.EphemeralNotificationsRepository
import org.dallasmakerspace.bambuddy.notifications.webhook.BambuddyWebhookPayload
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.notifications.Channel
import org.dallasmakerspace.notifications.CreateNotificationRequest
import org.dallasmakerspace.notifications.NotificationService
import org.dallasmakerspace.notifications.templates.BambuPrintActionRequiredData
import org.dallasmakerspace.notifications.templates.BambuPrintFinishedData
import org.dallasmakerspace.notifications.templates.BambuPrinterAvailableData
import org.dallasmakerspace.notifications.templates.BambuWatchNoticeData
import org.dallasmakerspace.notifications.templates.bambuStatusUrl
import org.dallasmakerspace.notifications.topics.BambuPrintActionRequiredTopic
import org.dallasmakerspace.notifications.topics.BambuPrintFinishedTopic
import org.dallasmakerspace.notifications.topics.BambuPrinterAvailableTopic
import org.dallasmakerspace.notifications.topics.BambuWatchTopic

private val PRINTING_STATES = BAMBU_PRINTING_STATES
private val TRIGGER_EVENTS = listOf("print_complete", "print_failed", "print_stopped")

/**
 * BamBuddy reads fetched once per sweeper pass and shared across all due watches in it
 * (design §10): the queue is fetched once total, printer status once per distinct printer.
 * A null [queue] means the queue read failed; a printer's status value is null when that
 * read failed / the printer was unreachable.
 */
data class ExpiryPrefetch(
    val queue: List<BambuQueueItem>?,
    val statuses: Map<Int, BambuPrinterStatus?>,
)

/**
 * The watch state machine (design §§8–10): arm / cancel / list, fire on webhook events, and
 * the expiry decision table. Every transition goes through the repository's conditional
 * claims.
 *
 * Claim + notification create are NOT one database transaction: in the pinned Exposed
 * 0.41.1, `newSuspendedTransaction` always starts a fresh transaction (it passes a null
 * current transaction), so the repositories' and NotificationService's own transaction
 * calls commit independently — see design §9 as amended. The end-state invariant is
 * instead guaranteed by compensation + reconciliation: if the create fails after a claim
 * committed, the claim is reverted to ARMED in the same call; if the process dies between
 * claim and create, the sweeper's reconciliation pass recreates the missing notice for the
 * stranded FIRED/EXPIRED tombstone (the per-watch dedupe key makes recreation return the
 * original row instead of double-sending) and links it.
 */
@Singleton
@Suppress("TooManyFunctions", "LongParameterList")
class EphemeralNotificationService
@Inject
constructor(
    private val watchRepository: EphemeralNotificationsRepository,
    private val apiClient: IBambuddyApiClient,
    private val notificationService: NotificationService,
    private val wakeSignal: BambuWakeSignal,
    loggerFactory: LoggerFactory,
) {
  private val log = loggerFactory.create(javaClass)
  private val json = Json { encodeDefaults = true }
  private val utcZone = ZoneId.of("UTC")

  // ---------------------------------------------------------------------------------------------
  // Arm / cancel / list
  // ---------------------------------------------------------------------------------------------

  suspend fun arm(actor: String, request: CreateBambuWatchRequest): BambuWatchDTO {
    val scope =
        try {
          WatchScope.valueOf(request.scope)
        } catch (e: IllegalArgumentException) {
          throw BambuWatchException(400, "Unknown watch scope: ${request.scope}")
        }
    try {
      val printers = apiClient.getPrinters()
      val printer =
          printers.firstOrNull { it.id == request.printerId }
              ?: throw BambuWatchException(404, "Printer not found")
      val queue = apiClient.getQueue()
      val status = apiClient.getPrinterStatus(printer.id)

      return when (scope) {
        WatchScope.OWN_JOB -> armOwnJob(actor, printer.id, printer.name, request.queueId, queue, status)
        WatchScope.PRINTER_AVAILABLE -> armPrinterAvailable(actor, printer.id, printer.name, queue, status)
      }
    } catch (e: BambuddyApiException) {
      // Upstream bodies/messages are operator detail, not member-facing copy: never leak them.
      log.warn("arm failed: Bambuddy API error: ${e.message}")
      throw BambuWatchException(
          502, "The printer service didn't respond properly. Please try again in a moment.")
    }
  }

  private suspend fun armOwnJob(
      actor: String,
      printerId: Int,
      printerName: String,
      queueId: Int?,
      queue: List<BambuQueueItem>,
      status: BambuPrinterStatus?,
  ): BambuWatchDTO {
    if (queueId == null) throw BambuWatchException(400, "queueId is required for an own-job watch")
    val row =
        queue.firstOrNull { it.id == queueId && it.printerId == printerId }
            ?: throw BambuWatchException(404, "That print is no longer in the queue")
    if (row.createdByUsername != actor) {
      throw BambuWatchException(403, "You can only watch your own prints")
    }
    if (row.status != "printing" && row.status != "pending") {
      throw BambuWatchException(409, "That print has already finished")
    }
    // A pending job also waits for the running job and the pending jobs queued ahead of it.
    val estimated =
        if (row.status == "printing") {
          status?.remainingTimeMinutes?.takeIf { it > 0 }
              ?: BambuTtl.estimateMinutes(row.printTimeSeconds)
        } else {
          BambuTtl.pendingFinishMinutes(row, queue, status)
        }
    val remaining = estimated ?: throw BambuWatchException(422, BambuTtl.NO_ESTIMATE_MESSAGE)
    return insertWatch(
        actor, WatchScope.OWN_JOB, printerId, printerName, row.id, row.archiveName, remaining)
  }

  private suspend fun armPrinterAvailable(
      actor: String,
      printerId: Int,
      printerName: String,
      queue: List<BambuQueueItem>,
      status: BambuPrinterStatus?,
  ): BambuWatchDTO {
    val state = bambuEffectiveState(status)
    val blocking = queue.firstOrNull { it.printerId == printerId && it.status == "printing" }
    if (state !in PRINTING_STATES || blocking == null) {
      throw BambuWatchException(
          409,
          when {
            // Printing, but no queue row for the running job (e.g. started straight from the
            // slicer): there is no blocking job to watch the end of.
            state in PRINTING_STATES ->
                "We can't tell which print is running on this printer yet — try again in a moment"
            state == "IDLE" -> "This printer is already available"
            state == "FINISH" -> "The last print on this printer finished — check the status page"
            state == "FAILED" -> "This printer needs attention — check the status page"
            else -> "This printer is offline"
          })
    }
    val remaining =
        status?.remainingTimeMinutes?.takeIf { it > 0 }
            ?: BambuTtl.estimateMinutes(blocking.printTimeSeconds)
            ?: throw BambuWatchException(422, BambuTtl.NO_ESTIMATE_MESSAGE)
    return insertWatch(
        actor,
        WatchScope.PRINTER_AVAILABLE,
        printerId,
        printerName,
        blocking.id,
        blocking.archiveName,
        remaining)
  }

  private suspend fun insertWatch(
      actor: String,
      scope: WatchScope,
      printerId: Int,
      printerName: String,
      queueId: Int,
      filename: String?,
      remainingMinutes: Int,
  ): BambuWatchDTO {
    val now = LocalDateTime.now(utcZone)
    // Single atomic check-and-insert in the repository: a concurrent duplicate click or
    // double-submit returns the already-armed watch instead of creating a second one.
    val (watch, created) =
        watchRepository.insertArmedIfAbsent(
            EphemeralNotification(
                id = 0,
                memberUsername = actor,
                scope = scope,
                printerId = printerId,
                printerName = printerName,
                queueId = queueId,
                filename = filename,
                jobName = filename,
                triggerEvents = TRIGGER_EVENTS,
                status = WatchStatus.ARMED,
                rearmCount = 0,
                createdAt = now,
                expiresAt = now.plusMinutes(BambuTtl.ttlMinutes(remainingMinutes).toLong()),
                terminalAt = null,
                firedNotificationId = null,
                terminalReason = null,
            ))
    if (created) {
      wakeSignal.wake()
      log.info(
          "watch=${watch.id} created member=$actor scope=${scope.name} printer=$printerId queue=$queueId expiresAt=${watch.expiresAt} rearm=0")
    }
    return watch.toDTO()
  }

  suspend fun listArmed(actor: String): List<BambuWatchDTO> =
      watchRepository.listArmed(actor).map { it.toDTO() }

  /**
   * Cancels the actor's watch. Returns the watch (terminal watches included, for idempotent
   * DELETEs), or null when the id is unknown or belongs to someone else — the route answers 404
   * either way so ownership is never leaked.
   */
  suspend fun cancel(actor: String, id: Long): BambuWatchDTO? {
    val watch = watchRepository.getById(id) ?: return null
    if (watch.memberUsername != actor) return null
    if (watch.status != WatchStatus.ARMED) return watch.toDTO()
    val won = watchRepository.claimForCancel(id, actor, LocalDateTime.now(utcZone))
    if (won) log.info("watch=$id cancelled member=$actor")
    return watchRepository.getById(id)?.toDTO() ?: watch.copy(status = WatchStatus.CANCELLED).toDTO()
  }

  // ---------------------------------------------------------------------------------------------
  // Fire (webhook + expiry backstop share this)
  // ---------------------------------------------------------------------------------------------

  /**
   * Fires [watch] with the real notice for [event] (a BamBuddy event string, or the inferred
   * terminal event at expiry). Returns true iff this call won the claim and created the notice
   * (a printer-available watch that is retargeted to pending work instead returns false).
   * If the create fails after the claim committed, the claim is reverted to ARMED and the
   * failure is rethrown — the watch is never left as a FIRED tombstone with nothing sent.
   */
  suspend fun fireWatch(
      watch: EphemeralNotification,
      event: String,
      payload: BambuddyWebhookPayload?,
      occurredAt: LocalDateTime,
  ): Boolean =
      fireOrRetarget(watch, event, payload, occurredAt, queue = null) == FireResult.FIRED

  private enum class FireResult {
    FIRED,
    RETARGETED,
    CLAIM_LOST,
  }

  /**
   * [fireWatch] plus the printer-available guard: when more work is queued for the printer it is
   * not free, so the watch moves to the next pending job instead of announcing "available".
   * [queue] null means fetch it now (a failed fetch skips the guard); an empty list skips it.
   */
  private suspend fun fireOrRetarget(
      watch: EphemeralNotification,
      event: String,
      payload: BambuddyWebhookPayload?,
      occurredAt: LocalDateTime,
      queue: List<BambuQueueItem>?,
  ): FireResult {
    if (watch.scope == WatchScope.PRINTER_AVAILABLE) {
      val known =
          queue
              ?: try {
                apiClient.getQueue()
              } catch (e: Exception) {
                log.warn("watch=${watch.id} pending-work check skipped: getQueue failed: ${e.message}")
                emptyList()
              }
      val next = BambuTtl.nextPending(known, watch.printerId)
      if (next != null) {
        return if (retargetToPending(watch, next, known)) FireResult.RETARGETED
        else FireResult.CLAIM_LOST
      }
    }
    return claimAndFire(watch, event, payload, occurredAt)
  }

  private suspend fun retargetToPending(
      watch: EphemeralNotification,
      next: BambuQueueItem,
      queue: List<BambuQueueItem>,
  ): Boolean {
    val remaining =
        BambuTtl.pendingFinishMinutes(next, queue, null) ?: BambuTtl.FALLBACK_ESTIMATE_MINUTES
    val won =
        watchRepository.claimForRetarget(
            watch.id,
            watch.queueId,
            LocalDateTime.now(utcZone).plusMinutes(BambuTtl.ttlMinutes(remaining).toLong()),
            next.id,
            next.archiveName,
            next.archiveName)
    if (won) {
      wakeSignal.wake()
      log.info("watch=${watch.id} retargeted to queue=${next.id}: printer has more queued work")
    }
    return won
  }

  private suspend fun claimAndFire(
      watch: EphemeralNotification,
      event: String,
      payload: BambuddyWebhookPayload?,
      occurredAt: LocalDateTime,
  ): FireResult {
    val (topic, templateName, payloadJson, dedupeKey) =
        realNotice(watch, event, payload, occurredAt)
    val won =
        watchRepository.claimForFiring(watch.id, LocalDateTime.now(utcZone), "Fired by $event")
    if (!won) return FireResult.CLAIM_LOST
    try {
      val notification =
          notificationService.create(
              CreateNotificationRequest(
                  memberUsername = watch.memberUsername,
                  topic = topic,
                  templateName = templateName,
                  payload = payloadJson,
                  channel = Channel.EMAIL,
                  dedupeKey = dedupeKey,
                  consentOverride = true,
              ))
      watchRepository.setFiredNotificationId(watch.id, notification.id)
    } catch (e: Exception) {
      revertTerminalClaimOrLog(watch.id, "fire ($event)")
      throw e
    }
    log.info("watch=${watch.id} fired member=${watch.memberUsername} event=$event")
    return FireResult.FIRED
  }

  /**
   * Reconciliation pass (P0 recovery): terminal tombstones whose notification was never
   * linked — the residue of a crash between the claim commit and the create — get their
   * notice recreated and linked. NotificationService.create is dedupe-key idempotent (a
   * repeat key returns the existing row), so recreating can never double-send. Returns how
   * many tombstones were recovered.
   */
  suspend fun reconcileTerminalWatches(limit: Int = 50): Int {
    var recovered = 0
    for (watch in watchRepository.findTerminalWithoutNotification(limit)) {
      try {
        val notification =
            when (watch.status) {
              WatchStatus.FIRED -> {
                val event =
                    watch.terminalReason
                        ?.removePrefix("Fired by ")
                        ?.takeIf { it in TRIGGER_EVENTS } ?: "print_complete"
                val (topic, templateName, payloadJson, dedupeKey) =
                    realNotice(watch, event, null, watch.terminalAt ?: LocalDateTime.now(utcZone))
                notificationService.create(
                    CreateNotificationRequest(
                        memberUsername = watch.memberUsername,
                        topic = topic,
                        templateName = templateName,
                        payload = payloadJson,
                        channel = Channel.EMAIL,
                        dedupeKey = dedupeKey,
                        consentOverride = true,
                    ))
              }
              WatchStatus.EXPIRED ->
                  notificationService.create(
                      CreateNotificationRequest(
                          memberUsername = watch.memberUsername,
                          topic = BambuWatchTopic,
                          templateName = "bambu-watch-expired",
                          payload = expiredPayload(watch),
                          channel = Channel.EMAIL,
                          dedupeKey = "bambu:watch:${watch.id}:expired",
                          consentOverride = true,
                      ))
              else -> null
            }
        if (notification != null) {
          watchRepository.setFiredNotificationId(watch.id, notification.id)
          recovered++
          log.warn(
              "watch=${watch.id} reconciled: recreated missing ${watch.status} notification ${notification.id}")
        }
      } catch (e: Exception) {
        log.error("Reconciliation failed for watch=${watch.id}: ${e.message}", e)
      }
    }
    return recovered
  }

  /** Best-effort compensation: puts a claimed-but-unnotified watch back to ARMED. */
  private suspend fun revertTerminalClaimOrLog(watchId: Long, what: String) {
    try {
      if (watchRepository.revertTerminalClaim(watchId)) {
        log.error(
            "watch=$watchId $what: notification create failed after the claim; claim reverted to ARMED for retry/reconciliation")
      } else {
        log.error(
            "watch=$watchId $what: notification create failed and the claim could not be reverted")
      }
    } catch (e: Exception) {
      log.error("watch=$watchId $what: claim revert failed: ${e.message}", e)
    }
  }

  private fun realNotice(
      watch: EphemeralNotification,
      event: String,
      payload: BambuddyWebhookPayload?,
      occurredAt: LocalDateTime,
  ): RealNotice {
    val dedupeKey = "bambu:${watch.printerId}:${watch.queueId ?: 0}:$event:${watch.id}"
    return when {
      watch.scope == WatchScope.PRINTER_AVAILABLE ->
          RealNotice(
              BambuPrinterAvailableTopic,
              "bambu-printer-available",
              json.encodeToString(
                  BambuPrinterAvailableData(
                      printerName = watch.printerName,
                      freedByJobName = watch.jobName,
                      occurredAtText = formatCentralTime(occurredAt),
                      statusPageUrl = bambuStatusUrl(),
                  )),
              dedupeKey)
      event == "print_complete" ->
          RealNotice(
              BambuPrintFinishedTopic,
              "bambu-print-finished",
              json.encodeToString(
                  BambuPrintFinishedData(
                      printerName = watch.printerName,
                      jobName = watch.jobName ?: "",
                      completedAtText = formatCentralTime(occurredAt),
                      durationText = payload?.duration,
                      filamentGramsText = payload?.filamentGrams?.let { "$it g" },
                      statusPageUrl = bambuStatusUrl(),
                  )),
              dedupeKey)
      else ->
          RealNotice(
              BambuPrintActionRequiredTopic,
              "bambu-print-action-required",
              json.encodeToString(
                  BambuPrintActionRequiredData(
                      printerName = watch.printerName,
                      jobName = watch.jobName ?: "",
                      eventLabel = if (event == "print_stopped") "was stopped" else "failed",
                      reason = payload?.reason,
                      occurredAtText = formatCentralTime(occurredAt),
                      statusPageUrl = bambuStatusUrl(),
                  )),
              dedupeKey)
    }
  }

  private data class RealNotice(
      val topic: org.dallasmakerspace.notifications.NotificationTopic,
      val templateName: String,
      val payloadJson: String,
      val dedupeKey: String,
  )

  // ---------------------------------------------------------------------------------------------
  // Expiry (design §10.2 decision table)
  // ---------------------------------------------------------------------------------------------

  /**
   * Processes one due ARMED watch: one fresh BamBuddy check, then fire / overdue+re-arm /
   * expire per the decision table. When [prefetch] is supplied (sweeper pass), its shared
   * queue/status reads are used instead of fetching per watch.
   */
  suspend fun processExpiredWatch(
      watch: EphemeralNotification,
      prefetch: ExpiryPrefetch? = null,
  ): ExpiryOutcome {
    val queue: List<BambuQueueItem>?
    val status: BambuPrinterStatus?
    if (prefetch != null) {
      queue = prefetch.queue
      status = prefetch.statuses[watch.printerId]
    } else {
      queue =
          try {
            apiClient.getQueue()
          } catch (e: Exception) {
            log.warn("Expiry check: getQueue failed for watch=${watch.id}: ${e.message}")
            null
          }
      status =
          try {
            apiClient.getPrinterStatus(watch.printerId)
          } catch (e: Exception) {
            log.warn("Expiry check: getPrinterStatus failed for watch=${watch.id}: ${e.message}")
            null
          }
    }
    if (queue == null) {
      // Only when BOTH reads failed is this "BamBuddy unreachable" (expire, no re-arm).
      // When the status read succeeded, it alone drives the decision table.
      if (status == null) {
        return expireForReal(watch, "BamBuddy unreachable at expiry check")
      }
      val state = bambuEffectiveState(status)
      return when {
        watch.scope == WatchScope.PRINTER_AVAILABLE && (state == "IDLE" || state == "FINISH") ->
            fireAtExpiry(watch, "print_complete", emptyList())
        state == "OFFLINE" -> expireForReal(watch, "Printer offline at expiry check")
        state in PRINTING_STATES &&
            watch.filename != null &&
            status.currentPrint == watch.filename -> overdue(watch, emptyList(), status, null)
        else ->
            expireForReal(
                watch, "Queue unavailable at expiry check and the watched job is not confirmed printing")
      }
    }

    val row = queue.firstOrNull { it.id == watch.queueId }
    val state = bambuEffectiveState(status)

    // The watched/blocking job ended while its webhook was lost: fire the real notice.
    if (row != null && (row.status == "completed" || row.status == "failed")) {
      val inferred = if (row.status == "completed") "print_complete" else "print_failed"
      return fireAtExpiry(watch, inferred, queue)
    }

    if (watch.scope == WatchScope.PRINTER_AVAILABLE && row == null) {
      // Blocking job gone: the printer is free if it's idle/finished, busy-with-another-job
      // (overdue path below) if something else is printing, and an expiry if it's offline.
      if (state == "IDLE" || state == "FINISH") {
        return fireAtExpiry(watch, "print_complete", queue)
      }
      if (state == "OFFLINE") {
        return expireForReal(watch, "Printer offline at expiry check")
      }
      if (state in PRINTING_STATES) {
        // A different job is printing now: the printer is not available. Overdue + re-arm,
        // retargeting the watch at the new blocking job (design §10.2).
        return overdue(watch, queue, status, row)
      }
    }

    val stillPrinting =
        (row != null && (row.status == "printing" || row.status == "pending")) ||
            (state in PRINTING_STATES &&
                watch.filename != null &&
                status?.currentPrint == watch.filename)
    if (stillPrinting) {
      return overdue(watch, queue, status, row)
    }

    return expireForReal(watch, "Job gone or printer not printing the watched job at expiry check")
  }

  private suspend fun fireAtExpiry(
      watch: EphemeralNotification,
      event: String,
      queue: List<BambuQueueItem>,
  ): ExpiryOutcome =
      when (fireOrRetarget(watch, event, null, LocalDateTime.now(utcZone), queue)) {
        FireResult.FIRED -> ExpiryOutcome.FIRED
        FireResult.RETARGETED -> ExpiryOutcome.RETARGETED
        FireResult.CLAIM_LOST -> ExpiryOutcome.CLAIM_LOST
      }

  /** Overdue notice + the single re-arm (or expire-for-real when no fresh estimate exists). */
  private suspend fun overdue(
      watch: EphemeralNotification,
      queue: List<BambuQueueItem>,
      status: BambuPrinterStatus?,
      row: BambuQueueItem?,
  ): ExpiryOutcome {
    if (watch.rearmCount >= 1) {
      return expireForReal(watch, "Re-armed watch expired with the job still not terminal")
    }

    // Fresh remaining: for a printer-available watch whose blocking job changed, retarget the
    // watch at the job now printing.
    var newQueueId: Int? = null
    var newFilename: String? = null
    var newJobName: String? = null
    var estimateRow = row
    if (watch.scope == WatchScope.PRINTER_AVAILABLE) {
      val currentBlocking =
          queue.firstOrNull { it.printerId == watch.printerId && it.status == "printing" }
      if (currentBlocking != null && currentBlocking.id != watch.queueId) {
        newQueueId = currentBlocking.id
        newFilename = currentBlocking.archiveName
        newJobName = currentBlocking.archiveName
        estimateRow = currentBlocking
      }
    }
    // The §8.3 source chain, same as at arm time: a pending row's TTL covers the queue ahead of it.
    val remaining =
        if (estimateRow?.status == "pending") {
          BambuTtl.pendingFinishMinutes(estimateRow, queue, status)
        } else {
          status?.remainingTimeMinutes?.takeIf { it > 0 }
              ?: BambuTtl.estimateMinutes(estimateRow?.printTimeSeconds)
        }

    val now = LocalDateTime.now(utcZone)
    if (remaining != null) {
      val wonRearm =
          watchRepository.claimForRearm(
              watch.id,
              now.plusMinutes(BambuTtl.ttlMinutes(remaining).toLong()),
              newQueueId,
              newFilename,
              newJobName)
      if (!wonRearm) return ExpiryOutcome.CLAIM_LOST
      wakeSignal.wake()
      try {
        val notification =
            notificationService.create(
                CreateNotificationRequest(
                    memberUsername = watch.memberUsername,
                    topic = BambuWatchTopic,
                    templateName = "bambu-watch-overdue",
                    payload = overduePayload(watch, newJobName),
                    channel = Channel.EMAIL,
                    dedupeKey = "bambu:watch:${watch.id}:overdue",
                    consentOverride = true,
                ))
        watchRepository.setFiredNotificationId(watch.id, notification.id)
      } catch (e: Exception) {
        // Undo the re-arm so the next pass retries the overdue path from the original state.
        try {
          watchRepository.revertRearm(
              watch.id, watch.expiresAt, watch.queueId, watch.filename, watch.jobName)
        } catch (revertError: Exception) {
          log.error("watch=${watch.id} re-arm revert failed: ${revertError.message}", revertError)
        }
        throw e
      }
      log.info("watch=${watch.id} expiry outcome=overdue-rearmed member=${watch.memberUsername}")
      return ExpiryOutcome.OVERDUE_REARMED
    }

    // No fresh estimate (design §10.2): do not re-arm — fall through to expire-for-real. The
    // overdue notice is still sent first (it doubles as the member's prompt), with copy that
    // is truthful for the terminal case (stillWatching = false), then the terminal notice.
    val wonExpiry =
        watchRepository.claimForExpiry(watch.id, now, "No fresh estimate at expiry; expired")
    if (!wonExpiry) return ExpiryOutcome.CLAIM_LOST
    try {
      notificationService.create(
          CreateNotificationRequest(
              memberUsername = watch.memberUsername,
              topic = BambuWatchTopic,
              templateName = "bambu-watch-overdue",
              payload = overduePayload(watch, newJobName, stillWatching = false),
              channel = Channel.EMAIL,
              dedupeKey = "bambu:watch:${watch.id}:overdue",
              consentOverride = true,
          ))
      val notification =
          notificationService.create(
              CreateNotificationRequest(
                  memberUsername = watch.memberUsername,
                  topic = BambuWatchTopic,
                  templateName = "bambu-watch-expired",
                  payload = expiredPayload(watch),
                  channel = Channel.EMAIL,
                  dedupeKey = "bambu:watch:${watch.id}:expired",
                  consentOverride = true,
              ))
      watchRepository.setFiredNotificationId(watch.id, notification.id)
    } catch (e: Exception) {
      revertTerminalClaimOrLog(watch.id, "expire (no fresh estimate)")
      throw e
    }
    log.info("watch=${watch.id} expiry outcome=expired member=${watch.memberUsername}")
    return ExpiryOutcome.EXPIRED
  }

  /** Terminal expiry: check-yourself notice + EXPIRED claim. */
  private suspend fun expireForReal(watch: EphemeralNotification, reason: String): ExpiryOutcome {
    val now = LocalDateTime.now(utcZone)
    val won = watchRepository.claimForExpiry(watch.id, now, reason)
    if (!won) return ExpiryOutcome.CLAIM_LOST
    try {
      val notification =
          notificationService.create(
              CreateNotificationRequest(
                  memberUsername = watch.memberUsername,
                  topic = BambuWatchTopic,
                  templateName = "bambu-watch-expired",
                  payload = expiredPayload(watch),
                  channel = Channel.EMAIL,
                  dedupeKey = "bambu:watch:${watch.id}:expired",
                  consentOverride = true,
              ))
      watchRepository.setFiredNotificationId(watch.id, notification.id)
    } catch (e: Exception) {
      revertTerminalClaimOrLog(watch.id, "expire")
      throw e
    }
    log.info("watch=${watch.id} expired member=${watch.memberUsername} reason=$reason")
    return ExpiryOutcome.EXPIRED
  }

  private fun overduePayload(
      watch: EphemeralNotification,
      newJobName: String?,
      stillWatching: Boolean = true,
  ): String =
      json.encodeToString(
          BambuWatchNoticeData(
              printerName = watch.printerName,
              jobName = newJobName ?: watch.jobName,
              scopeLabel =
                  if (watch.scope == WatchScope.OWN_JOB) "your print"
                  else "the print you were waiting on",
              statusPageUrl = bambuStatusUrl(),
              stillWatching = stillWatching,
          ))

  private fun expiredPayload(watch: EphemeralNotification): String =
      json.encodeToString(
          BambuWatchNoticeData(
              printerName = watch.printerName,
              jobName = watch.jobName,
              scopeLabel =
                  if (watch.scope == WatchScope.OWN_JOB) "your print"
                  else "the print you were waiting on",
              statusPageUrl = bambuStatusUrl(),
          ))
}

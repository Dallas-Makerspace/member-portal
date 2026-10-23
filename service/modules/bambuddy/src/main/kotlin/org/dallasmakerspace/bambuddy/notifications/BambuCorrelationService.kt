package org.dallasmakerspace.bambuddy.notifications

import javax.inject.Inject
import javax.inject.Singleton
import org.dallasmakerspace.bambuddy.BambuQueueItem
import org.dallasmakerspace.bambuddy.IBambuddyApiClient
import org.dallasmakerspace.bambuddy.notifications.db.EphemeralNotificationsRepository
import org.dallasmakerspace.bambuddy.notifications.webhook.BambuddyWebhookPayload
import org.dallasmakerspace.core.LoggerFactory

/** How a webhook print event was correlated to watches (logged, never the payload itself). */
enum class CorrelationMode {
  FIRED,
  NO_PRINTER,
  NO_WATCH,
  FILENAME_ONLY,
  UNVERIFIED,
  UNVERIFIED_NO_FILENAME,
  STATE_MISMATCH,
}

data class CorrelationResult(
    val mode: CorrelationMode,
    val printerId: Int?,
    val watches: List<EphemeralNotification> = emptyList(),
)

/**
 * Answers the narrow correlation question for a print event (design §7): did the job a watch
 * points at just end, in the way this event claims? Correlation is anchored on the watch's stored
 * queue id; the payload filename is a cross-check, never the selector — completed/failed queue
 * rows are permanent history, so "newest row with this filename" would credit reprints and stale
 * rows (the lingering-FINISH trap).
 */
@Singleton
class BambuCorrelationService
@Inject
constructor(
    private val apiClient: IBambuddyApiClient,
    private val watchRepository: EphemeralNotificationsRepository,
    loggerFactory: LoggerFactory,
) {
  private val log = loggerFactory.create(javaClass)

  /** Resolves a BamBuddy printer NAME to its id: exact match, else a unique case-insensitive one. */
  suspend fun resolvePrinterId(printerName: String): Int? {
    val printers =
        try {
          apiClient.getPrinters()
        } catch (e: Exception) {
          log.warn("Correlation: getPrinters failed: ${e.message}")
          return null
        }
    return resolvePrinterId(printers.map { it.id to it.name }, printerName)
  }

  internal fun resolvePrinterId(printers: List<Pair<Int, String>>, printerName: String): Int? {
    printers.firstOrNull { it.second == printerName }?.let {
      return it.first
    }
    val insensitive = printers.filter { it.second.equals(printerName, ignoreCase = true) }
    return insensitive.singleOrNull()?.first
  }

  /**
   * Correlates a print_complete/print_failed/print_stopped event to the ARMED watches it fires.
   * Performs one fresh lookup (printers + queue), deliberately bypassing the status page's cache.
   */
  suspend fun correlatePrintEvent(payload: BambuddyWebhookPayload): CorrelationResult {
    val printerName = payload.printer
    if (printerName.isNullOrBlank()) {
      return CorrelationResult(CorrelationMode.NO_WATCH, printerId = null)
    }

    val printers =
        try {
          apiClient.getPrinters()
        } catch (e: Exception) {
          log.warn("Correlation: getPrinters failed, falling back to unverified: ${e.message}")
          return unverified(printerName, payload)
        }
    val printerId =
        resolvePrinterId(printers.map { it.id to it.name }, printerName)
            ?: return CorrelationResult(CorrelationMode.NO_PRINTER, printerId = null)

    val queue =
        try {
          apiClient.getQueue()
        } catch (e: Exception) {
          log.warn("Correlation: getQueue failed, falling back to unverified: ${e.message}")
          return unverified(printerId, printerName, payload)
        }

    val candidates = watchRepository.findArmedByPrinter(printerId)
    if (candidates.isEmpty()) {
      return CorrelationResult(CorrelationMode.NO_WATCH, printerId)
    }

    val printerQueue = queue.filter { it.printerId == printerId }
    val filename = payload.filename?.trim()?.takeIf { it.isNotEmpty() }
    val fired = mutableListOf<EphemeralNotification>()
    var sawStateMismatch = false
    // Without a payload filename there is nothing to cross-check a watch's job against, so the
    // queue-id path must not fire at all (design §7.4): own-job watches stay ARMED for the TTL
    // backstop, and only the available-watch fallback below may fire.
    if (filename != null) {
      for (watch in candidates) {
        if (payload.event !in watch.triggerEvents) continue
        val row = printerQueue.firstOrNull { it.id == watch.queueId }
        when {
          row == null -> Unit // handled by the filename-only fallback below
          !filenameMatches(row, filename) -> Unit // a different job ended; not this watch's
          isTerminalConsistent(row.status, payload.event) -> fired += watch
          else -> sawStateMismatch = true // row still printing/pending: event raced the queue
        }
      }
    }
    if (fired.isNotEmpty()) {
      return CorrelationResult(CorrelationMode.FIRED, printerId, fired)
    }

    // Vanished queue row fallback: exactly one candidate watch whose stored filename matches.
    // This is evaluated BEFORE reporting a state mismatch: a mismatch on one watch must not
    // suppress the unambiguous filename-only fire of a different watch whose row vanished.
    if (filename != null) {
      val vanished =
          candidates.filter { watch ->
            payload.event in watch.triggerEvents &&
                printerQueue.none { it.id == watch.queueId } &&
                watch.filename?.trim() == filename
          }
      if (vanished.size == 1) {
        return CorrelationResult(CorrelationMode.FILENAME_ONLY, printerId, vanished)
      }
      if (sawStateMismatch) {
        return CorrelationResult(CorrelationMode.STATE_MISMATCH, printerId)
      }
      if (vanished.size > 1) {
        log.warn(
            "Correlation: filename-only fallback ambiguous for printer $printerId (${vanished.size} watches)")
        return CorrelationResult(CorrelationMode.NO_WATCH, printerId)
      }
    }
    if (sawStateMismatch) {
      return CorrelationResult(CorrelationMode.STATE_MISMATCH, printerId)
    }

    // Payload filename missing: an available watch may still fire if its blocking row is terminal.
    if (filename == null) {
      val availableFired =
          candidates.filter { watch ->
            watch.scope == WatchScope.PRINTER_AVAILABLE &&
                payload.event in watch.triggerEvents &&
                printerQueue
                    .firstOrNull { it.id == watch.queueId }
                    ?.let { isTerminalConsistent(it.status, payload.event) } == true
          }
      if (availableFired.isNotEmpty()) {
        return CorrelationResult(CorrelationMode.FIRED, printerId, availableFired)
      }
    }

    return CorrelationResult(CorrelationMode.NO_WATCH, printerId)
  }

  /** BamBuddy unreachable at receipt: fire on the sender's own event, matched from stored fields. */
  private suspend fun unverified(
      printerName: String,
      payload: BambuddyWebhookPayload,
  ): CorrelationResult {
    // Printer id unknown without the printers call; match watches by stored printer name instead.
    val filename = payload.filename?.trim()?.takeIf { it.isNotEmpty() }
    if (filename == null) {
      // Unverified mode has no queue-id anchor: without a filename there is nothing to
      // discriminate watches with, and matching on printer alone would fire every watch.
      log.info(
          "Correlation: unverified-no-filename printer=${sanitizeForLog(printerName)} event=${sanitizeForLog(payload.event)}")
      return CorrelationResult(CorrelationMode.UNVERIFIED_NO_FILENAME, printerId = null)
    }
    val watches =
        watchRepository
            .findArmedByPrinterName(printerName)
            .filter { watch ->
              payload.event in watch.triggerEvents && watch.filename?.trim() == filename
            }
    return unverifiedResult(watches, printerId = null, printerName)
  }

  private suspend fun unverified(
      printerId: Int,
      printerName: String,
      payload: BambuddyWebhookPayload,
  ): CorrelationResult {
    val filename = payload.filename?.trim()?.takeIf { it.isNotEmpty() }
    if (filename == null) {
      log.info(
          "Correlation: unverified-no-filename printer=${sanitizeForLog(printerName)} event=${sanitizeForLog(payload.event)}")
      return CorrelationResult(CorrelationMode.UNVERIFIED_NO_FILENAME, printerId)
    }
    val watches =
        watchRepository.findArmedByPrinter(printerId).filter { watch ->
          payload.event in watch.triggerEvents && watch.filename?.trim() == filename
        }
    return unverifiedResult(watches, printerId, printerName)
  }

  /**
   * Ambiguity discipline for the unverified fallback: it matches on printer + filename only
   * (the queue-id anchor is unavailable), so same-filename reprints are indistinguishable —
   * when more than one candidate matches, fire NONE and let the TTL backstop resolve it.
   */
  private fun unverifiedResult(
      watches: List<EphemeralNotification>,
      printerId: Int?,
      printerName: String,
  ): CorrelationResult =
      when {
        watches.isEmpty() -> CorrelationResult(CorrelationMode.NO_WATCH, printerId)
        watches.size > 1 -> {
          log.warn(
              "Correlation: unverified match ambiguous for printer=${sanitizeForLog(printerName)} (${watches.size} watches share the filename); firing none")
          CorrelationResult(CorrelationMode.NO_WATCH, printerId)
        }
        else -> CorrelationResult(CorrelationMode.UNVERIFIED, printerId, watches)
      }

  private fun filenameMatches(row: BambuQueueItem, filename: String): Boolean =
      row.archiveName?.trim() == filename

  /**
   * Whether a queue row's status is a terminal state consistent with the event. The event type —
   * not the queue row — decides which template fires; a stopped job may sit in either terminal
   * status (the queue vocabulary for stopped jobs must be confirmed against the live instance).
   */
  internal fun isTerminalConsistent(queueStatus: String, event: String): Boolean =
      when (event) {
        "print_complete" -> queueStatus == "completed"
        "print_failed" -> queueStatus == "failed"
        "print_stopped" -> queueStatus == "failed" || queueStatus == "completed"
        else -> queueStatus == "completed" || queueStatus == "failed"
      }
}

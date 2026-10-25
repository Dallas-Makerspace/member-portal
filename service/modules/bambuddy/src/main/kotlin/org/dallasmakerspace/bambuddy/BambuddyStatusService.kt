package org.dallasmakerspace.bambuddy

import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import org.dallasmakerspace.core.LoggerFactory

private const val CACHE_TTL_MS = 10_000L

private val PRINTING_STATES = BAMBU_PRINTING_STATES
// FAILED is a genuine problem - the printer needs a person's attention. FINISH is its own,
// separate bucket (not "needs attention"): a Bambu printer's firmware only reports whether a NEW
// print has started since the last one finished, not whether anyone has physically cleared the
// bed - so FINISH can persist indefinitely after a member already removed the part and just never
// started another print. Bambuddy exposes an `awaiting_plate_clear` flag that looked like it might
// help, but checking it against a live instance showed it's unreliable here (true even for
// actively-RUNNING printers) - most likely a manual "click to confirm cleared" bookkeeping flag
// (see /printers/{id}/clear-plate) rather than an automatic camera-based detection, so it's not
// used. Lumping FINISH into "needs attention" was actively misleading (see the linked discussion).
private val NEEDS_ATTENTION_STATES = setOf("FAILED")
private val FINISHED_STATES = setOf("FINISH")
private val FAILED_QUEUE_STATUSES = setOf("failed", "cancelled")

@Serializable
data class BambuStatusSummaryDTO(
    val available: Int,
    val printing: Int,
    val needsAttention: Int,
    val finished: Int,
    val offline: Int,
    val total: Int,
)

@Serializable
data class BambuPrinterDetailDTO(
    val id: Int,
    val name: String,
    val model: String?,
    val state: String,
    val progress: Int?,
    val remainingTimeMinutes: Int?,
    val layerNum: Int?,
    val totalLayers: Int?,
    val currentJobName: String?,
    val nextJobName: String?,
    // Queue ids behind currentJobName/nextJobName (same rows they're selected from), so the UI
    // can name the exact job a "notify me" click refers to. Additive; older UI builds ignore them.
    val currentQueueId: Int? = null,
    val nextQueueId: Int? = null,
    // Whoever queued the currently-printing (or just-failed) job, from the queue item's
    // created_by_username - null when idle/finished/offline, or when Bambuddy has no queue record
    // for the job (e.g. started directly from the slicer rather than through Bambuddy's queue).
    val printedByUsername: String?,
    // Whoever queued the most recently *completed* job on this printer - only populated in the
    // FINISH state (see NEEDS_ATTENTION_STATES/FINISHED_STATES comment: FINISH means the print sitting
    // on the bed hasn't necessarily been claimed yet), so members can tell whose part to remove.
    val previousPrintedByUsername: String?,
    // Whoever queued the next (pending) job, so the UI can offer its owner a watch on it.
    val nextPrintedByUsername: String? = null,
)

/**
 * Thin service layer over [IBambuddyApiClient] with a short-TTL in-memory cache - printer status
 * changes frequently, but this feature has modest concurrent viewership, so a heavier
 * background-refresh loop (like FeaturedProjectsService's daily cache) isn't warranted; a short
 * cache just dedupes bursts of near-simultaneous page loads. Fails soft to the last known snapshot
 * (or empty on first failure) rather than surfacing an error to the page - matches
 * RemoteAccessService's approach to Guacamole being unreachable.
 *
 * Bambuddy's printer-list endpoint carries no live state (see [BambuPrinter]) - status is a
 * separate call per printer ([IBambuddyApiClient.getPrinterStatus]), fetched concurrently for all
 * printers here since there's no bulk-status endpoint this integration's API key can use.
 */
@Singleton
class BambuddyStatusService
@Inject
constructor(
    private val bambuddyApiClient: IBambuddyApiClient,
    loggerFactory: LoggerFactory,
) {
  private val log = loggerFactory.create(javaClass)

  private data class Snapshot(
      val printers: List<BambuPrinter>,
      val statusByPrinterId: Map<Int, BambuPrinterStatus>,
      val queue: List<BambuQueueItem>,
      val fetchedAtMs: Long,
  )

  /** Effective state — see [bambuEffectiveState] (shared with the watch domain). */
  private fun effectiveState(status: BambuPrinterStatus?): String = bambuEffectiveState(status)

  private val cache = AtomicReference<Snapshot?>(null)

  private suspend fun getSnapshot(): Snapshot {
    val cached = cache.get()
    if (cached != null && System.currentTimeMillis() - cached.fetchedAtMs < CACHE_TTL_MS) {
      return cached
    }
    return coroutineScope {
      val printers =
          try {
            bambuddyApiClient.getPrinters()
          } catch (e: Exception) {
            log.warn("Failed to fetch Bambuddy printers; using last known snapshot", e)
            cached?.printers ?: emptyList()
          }
      val statusDeferred =
          printers.map { printer ->
            async {
              printer.id to
                  try {
                    bambuddyApiClient.getPrinterStatus(printer.id)
                  } catch (e: Exception) {
                    log.warn("Failed to fetch Bambuddy status for printer ${printer.id}", e)
                    null
                  }
            }
          }
      val queueDeferred = async {
        try {
          bambuddyApiClient.getQueue()
        } catch (e: Exception) {
          log.warn("Failed to fetch Bambuddy queue; using last known snapshot", e)
          cached?.queue ?: emptyList()
        }
      }
      val statusByPrinterId =
          statusDeferred.awaitAll().mapNotNull { (id, status) -> status?.let { id to it } }.toMap()
      val fresh =
          Snapshot(printers, statusByPrinterId, queueDeferred.await(), System.currentTimeMillis())
      cache.set(fresh)
      fresh
    }
  }

  suspend fun getSummary(): BambuStatusSummaryDTO {
    val snapshot = getSnapshot()
    val states = snapshot.printers.map { effectiveState(snapshot.statusByPrinterId[it.id]) }
    val printing = states.count { it in PRINTING_STATES }
    val needsAttention = states.count { it in NEEDS_ATTENTION_STATES }
    val finished = states.count { it in FINISHED_STATES }
    val offline =
        states.count {
          it !in PRINTING_STATES && it !in NEEDS_ATTENTION_STATES && it !in FINISHED_STATES &&
              it != "IDLE"
        }
    val available = states.size - printing - needsAttention - finished - offline
    return BambuStatusSummaryDTO(
        available = available,
        printing = printing,
        needsAttention = needsAttention,
        finished = finished,
        offline = offline,
        total = states.size)
  }

  suspend fun getPrinterDetails(): List<BambuPrinterDetailDTO> {
    val snapshot = getSnapshot()
    val queueByPrinter = snapshot.queue.groupBy { it.printerId }
    return snapshot.printers.map { printer ->
      val status = snapshot.statusByPrinterId[printer.id]
      val state = effectiveState(status)
      val printerQueue = queueByPrinter[printer.id].orEmpty().sortedBy { it.position }
      // At most one row per printer is ever "printing", so position ordering is safe here.
      val activePrint = printerQueue.firstOrNull { it.status == "printing" }
      // "failed" rows are permanent history, not transient: every failure a printer has ever had
      // stays in the queue as status="failed" with position=1, so selecting by position returns
      // the *oldest* failure (potentially months old) and credits the wrong member on every load.
      // Only consult them when the printer is actually FAILED, and take the newest by completedAt
      // - the same reasoning as `previous` below. A stopped print also leaves the printer FAILED,
      // but its queue row is "cancelled".
      val failedPrint =
          if (state == "FAILED") {
            printerQueue
                .filter { it.status in FAILED_QUEUE_STATUSES && it.completedAt != null }
                .maxByOrNull { it.completedAt!! }
          } else {
            null
          }
      val current = activePrint ?: failedPrint
      val next = printerQueue.firstOrNull { it.status == "pending" }
      // Position only orders pending/printing items - completed items need completedAt instead.
      val previous =
          printerQueue
              .filter { it.status == "completed" && it.completedAt != null }
              .maxByOrNull { it.completedAt!! }
      BambuPrinterDetailDTO(
          id = printer.id,
          name = printer.name,
          model = printer.model,
          state = state,
          progress = status?.progress?.let { Math.round(it).toInt() },
          remainingTimeMinutes = status?.remainingTimeMinutes,
          layerNum = status?.layerNum,
          totalLayers = status?.totalLayers,
          currentJobName = status?.currentPrint,
          nextJobName = next?.archiveName,
          currentQueueId = current?.id,
          nextQueueId = next?.id,
          printedByUsername = current?.createdByUsername,
          previousPrintedByUsername = previous?.createdByUsername,
          nextPrintedByUsername = next?.createdByUsername,
      )
    }
  }

  suspend fun getCameraSnapshot(printerId: Int): Pair<ByteArray, String>? =
      bambuddyApiClient.getCameraSnapshot(printerId)

  suspend fun getPrinterModelIcon(model: String): Pair<ByteArray, String>? =
      bambuddyApiClient.getPrinterModelIcon(model)
}

package org.dallasmakerspace.bambuddy.notifications

import org.dallasmakerspace.bambuddy.BAMBU_PRINTING_STATES
import org.dallasmakerspace.bambuddy.BambuPrinterStatus
import org.dallasmakerspace.bambuddy.BambuQueueItem
import org.dallasmakerspace.bambuddy.bambuEffectiveState

/**
 * Watch TTL computation (design §8.3). All values in minutes.
 *
 * TTL = remaining + grace, where grace = clamp(ceil(10% of remaining), 60, 180). Worked examples:
 * 20 → 80; 300 → 360; 600 → 660; 1200 → 1320; 2400 → 2580.
 */
object BambuTtl {

  fun ttlMinutes(remainingMinutes: Int): Int {
    require(remainingMinutes > 0) { "remainingMinutes must be positive, was $remainingMinutes" }
    val grace = ((remainingMinutes + 9) / 10).coerceIn(60, 180) // ceil(10%), clamped
    return remainingMinutes + grace
  }

  /** Slicer estimate from a queue row's print_time_seconds, rounded up to whole minutes. */
  fun estimateMinutes(printTimeSeconds: Int?): Int? {
    val seconds = printTimeSeconds ?: return null
    if (seconds <= 0) return null
    return (seconds + 59) / 60
  }

  /** Stand-in remaining time when a retargeted job has no slicer estimate. */
  const val FALLBACK_ESTIMATE_MINUTES = 60

  /**
   * Minutes until the pending [row] finishes: the job currently printing on its printer (live
   * remaining time, else its estimate), plus pending rows queued ahead of it, plus its own
   * estimate. Null when [row] has no estimate of its own.
   */
  fun pendingFinishMinutes(
      row: BambuQueueItem,
      queue: List<BambuQueueItem>,
      status: BambuPrinterStatus?,
  ): Int? {
    val own = estimateMinutes(row.printTimeSeconds) ?: return null
    val onPrinter = queue.filter { it.printerId == row.printerId }
    val running = onPrinter.firstOrNull { it.status == "printing" }
    val busy = running != null || bambuEffectiveState(status) in BAMBU_PRINTING_STATES
    val runningMinutes =
        if (!busy) {
          0
        } else {
          status?.remainingTimeMinutes?.takeIf { it > 0 }
              ?: estimateMinutes(running?.printTimeSeconds)
              ?: 0
        }
    val ahead =
        onPrinter
            .filter { it.status == "pending" && it.id != row.id && queuesBefore(it, row) }
            .sumOf { estimateMinutes(it.printTimeSeconds) ?: 0 }
    return runningMinutes + ahead + own
  }

  /** The next pending job on [printerId] (lowest queue position, then id), if any. */
  fun nextPending(queue: List<BambuQueueItem>, printerId: Int): BambuQueueItem? =
      queue
          .filter { it.printerId == printerId && it.status == "pending" }
          .minWithOrNull(compareBy<BambuQueueItem>({ it.position }, { it.id }))

  private fun queuesBefore(a: BambuQueueItem, b: BambuQueueItem): Boolean =
      a.position < b.position || (a.position == b.position && a.id < b.id)

  /** The §8.3 no-estimate rejection message (member-facing; the arm button stays for a retry). */
  const val NO_ESTIMATE_MESSAGE =
      "We can't estimate how long this print will run yet, so we can't set a reminder for it. " +
          "Try again in a moment, once printing is under way."
}

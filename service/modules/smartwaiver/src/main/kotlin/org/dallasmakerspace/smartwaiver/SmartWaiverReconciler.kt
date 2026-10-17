package org.dallasmakerspace.smartwaiver

import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

private val FIRST_YEAR = 2014

/**
 * Compares what SmartWaiver says it holds with what the mirror holds, per year and (only where a
 * year differs) per month. Costs one search call per year plus one per month of any differing year.
 * This report gates each cut-over step: it must show no gaps before reports move to local data.
 */
@Singleton
class SmartWaiverReconciler
@Inject
constructor(
    private val client: ISmartwaiverApiClient,
    private val repository: SmartWaiverMirrorRepository,
) {

  suspend fun report(today: LocalDate = LocalDate.now()): String {
    val local = repository.countsByMonth()
    val lines = mutableListOf<String>()
    var gaps = 0
    var swTotal = 0
    var localTotal = 0

    for (year in FIRST_YEAR..today.year) {
      val start = LocalDate.of(year, 1, 1)
      val end = minOf(LocalDate.of(year, 12, 31), today)
      val sw = client.countWaivers(start, end)
      val here = local.filterKeys { it.startsWith("$year-") }.values.sum()
      swTotal += sw
      localTotal += here
      lines += "%d  smartwaiver=%-6d mirror=%-6d %s".format(year, sw, here, status(sw, here))
      if (sw != here) {
        gaps++
        for (month in 1..12) {
          val first = LocalDate.of(year, month, 1)
          if (first.isAfter(end)) break
          val last = minOf(first.plusMonths(1).minusDays(1), end)
          val swMonth = client.countWaivers(first, last)
          val key = "%d-%02d".format(year, month)
          val mirrorMonth = local[key] ?: 0
          if (swMonth != mirrorMonth) {
            lines += "    $key  smartwaiver=%-6d mirror=%-6d %s".format(swMonth, mirrorMonth, status(swMonth, mirrorMonth))
          }
        }
      }
    }

    val waiting = repository.countNeedingMirror()
    val partial = repository.countIncompletePhotos()
    val noParticipants = repository.countMirroredWithoutParticipants()
    val checkins = repository.countCheckins()
    val ok = gaps == 0 && waiting == 0 && partial == 0 && noParticipants == 0
    return buildString {
      appendLine("SmartWaiver mirror reconciliation")
      lines.forEach { appendLine(it) }
      appendLine()
      appendLine("total: smartwaiver=$swTotal mirror=$localTotal")
      appendLine("waivers still to mirror in full: $waiting")
      appendLine("waivers with incomplete photos: $partial")
      appendLine("mirrored waivers without participants: $noParticipants")
      appendLine("check-ins mirrored: $checkins")
      appendLine()
      appendLine(if (ok) "RESULT: OK, no gaps" else "RESULT: GAPS FOUND")
    }
  }

  private fun status(sw: Int, mirror: Int) =
      when {
        sw == mirror -> "ok"
        mirror < sw -> "MISSING ${sw - mirror}"
        else -> "EXTRA ${mirror - sw}"
      }
}

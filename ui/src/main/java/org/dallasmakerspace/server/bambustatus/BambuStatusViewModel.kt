package org.dallasmakerspace.server.bambustatus

import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// States that show the progress bar / current-job block (PAUSE still has a meaningful, if frozen,
// progress/remaining-time to show). Badge color is decided separately below so PAUSE/FAILED/FINISH
// each get their own color instead of blending into "printing" or "available".
private val ACTIVE_JOB_STATES = setOf("RUNNING", "PREPARE", "SLICING", "PAUSE")
// FAILED is a genuine problem. FINISH is tracked separately (see isFinished below) rather than
// folded in here - a Bambu printer's firmware only reports whether a NEW print has started since
// the last one finished, not whether a person has physically cleared the bed, so FINISH can (and
// in practice does) persist long after someone already removed the part - lumping it into "needs
// attention" overcounts printers that are probably already free to use. Bambuddy's
// `awaiting_plate_clear` flag looked like it might help but proved unreliable when checked against
// a live instance (true even for actively-RUNNING printers).
private val NEEDS_ATTENTION_STATES = setOf("FAILED")

private val CHICAGO_ZONE = ZoneId.of("America/Chicago")
private val ETA_SAME_DAY_FORMATTER = DateTimeFormatter.ofPattern("h:mm a")
private val ETA_OTHER_DAY_FORMATTER = DateTimeFormatter.ofPattern("EEE h:mm a")

/** "1h 12m" / "45m" / "" (unknown) - kept out of Thymeleaf so it's unit-testable without Ktor. */
fun formatRemainingTime(minutes: Number?): String {
  val total = minutes?.toInt() ?: return ""
  if (total <= 0) return ""
  val hours = total / 60
  val mins = total % 60
  return if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
}

/** "3:42 PM" for today, "Sat 3:42 PM" for any other day - matches Bambuddy's own ETA display, so
 * the two are easy to cross-check. [now] is a parameter (not always Instant.now()) so this is
 * unit-testable without a clock dependency. */
fun formatEta(remainingMinutes: Number?, now: Instant = Instant.now()): String {
  val minutes = remainingMinutes?.toLong() ?: return ""
  if (minutes <= 0) return ""
  val nowChicago = now.atZone(CHICAGO_ZONE)
  val etaChicago = now.plusSeconds(minutes * 60).atZone(CHICAGO_ZONE)
  val formatter = if (etaChicago.toLocalDate() == nowChicago.toLocalDate()) {
    ETA_SAME_DAY_FORMATTER
  } else {
    ETA_OTHER_DAY_FORMATTER
  }
  return formatter.format(etaChicago)
}

// Used by the quick-filter buttons on the page - a single bucket name per card rather than
// re-deriving it from isPrinting/isIdle/needsAttention/isFinished/isOffline in the template or JS.
private fun resolveFilterCategory(
    needsAttention: Boolean,
    isFinished: Boolean,
    isOffline: Boolean,
    isIdle: Boolean,
): String =
    when {
      needsAttention -> "attention"
      isFinished -> "finished"
      isOffline -> "offline"
      isIdle -> "available"
      else -> "printing"
    }

// Derived from the flags below (not a second, parallel match on `state`) so this can never drift
// from isPrinting/isIdle/isOffline the way a separate `when` easily could.
private fun resolveBadgeClass(state: String, isOffline: Boolean, isIdle: Boolean): String =
    when {
      state == "FAILED" -> "text-bg-danger"
      state == "FINISH" -> "text-bg-info"
      state == "PAUSE" -> "text-bg-warning"
      isOffline -> "text-bg-secondary"
      isIdle -> "text-bg-success"
      else -> "text-bg-primary" // RUNNING, PREPARE, SLICING
    }

private fun resolveStateLabel(state: String): String =
    when (state) {
      "RUNNING" -> "Printing"
      "PREPARE" -> "Preparing"
      "SLICING" -> "Slicing"
      "PAUSE" -> "Paused"
      "IDLE" -> "Available"
      "FINISH" -> "Done - remove part"
      "FAILED" -> "Failed"
      "OFFLINE" -> "Offline"
      else -> "Unknown"
    }

/**
 * Reshapes one raw printer map (as returned by the backend's /bambu/printers) into the view model
 * `bambu-printer-status.html` renders - resolving a display-friendly state label/badge color and a
 * human-readable remaining-time string, so the template stays free of business logic. Extracted as
 * a pure function so it's unit-testable without Ktor, matching the `buildMachineViewModel`
 * convention used for Remote Access. The state-to-label/badge/filter resolution is split into the
 * small named functions above rather than inlined here, to keep this one readable.
 *
 * [viewerUsername]/[watches]/[notificationsEnabled] drive the notification buttons: a watch
 * button renders only for the viewer's own jobs (or, for printer-available, on a busy printer),
 * and only when the notifications feature is on for the viewer.
 */
fun buildPrinterViewModel(
    printer: Map<String, Any?>,
    viewerUsername: String? = null,
    watches: List<Map<String, Any?>> = emptyList(),
    notificationsEnabled: Boolean = false,
): Map<String, Any?> {
  val state = (printer["state"] as? String) ?: "UNKNOWN"
  val isPrinting = state in ACTIVE_JOB_STATES
  val needsAttention = state in NEEDS_ATTENTION_STATES
  val isFinished = state == "FINISH"
  val isIdle = state == "IDLE"
  // Anything not explicitly recognized (including the "UNKNOWN" default above) is treated the same
  // as OFFLINE - an unrecognized firmware state should never be colored/labeled as if it were safe
  // to use, matching how BambuddyStatusService.getSummary() fails the same state toward "offline".
  val isOffline = !isPrinting && !needsAttention && !isFinished && !isIdle
  val filterCategory = resolveFilterCategory(needsAttention, isFinished, isOffline, isIdle)
  val badgeClass = resolveBadgeClass(state, isOffline, isIdle)
  val stateLabel = resolveStateLabel(state)
  val progress = (printer["progress"] as? Number)?.toInt()
  val layerNum = (printer["layerNum"] as? Number)?.toInt()
  val totalLayers = (printer["totalLayers"] as? Number)?.toInt()
  val name = printer["name"] as? String ?: ""
  val model = printer["model"] as? String
  val currentJobName = printer["currentJobName"] as? String
  val nextJobName = printer["nextJobName"] as? String
  val previousPrintedByUsername = printer["previousPrintedByUsername"] as? String
  val printedByUsername = printer["printedByUsername"] as? String
  val nextPrintedByUsername = printer["nextPrintedByUsername"] as? String
  val printerId = (printer["id"] as? Number)?.toLong()
  val currentQueueId = (printer["currentQueueId"] as? Number)?.toLong()
  val nextQueueId = (printer["nextQueueId"] as? Number)?.toLong()
  val isOwnCurrentJob =
      notificationsEnabled && viewerUsername != null && currentQueueId != null &&
          printedByUsername == viewerUsername
  val isOwnNextJob =
      notificationsEnabled && viewerUsername != null && nextQueueId != null &&
          nextPrintedByUsername == viewerUsername
  fun watchIdFor(scope: String, queueId: Long?): Long? {
    if (!notificationsEnabled || printerId == null || queueId == null) return null
    return watches
        .firstOrNull {
          it["scope"] == scope &&
              (it["printerId"] as? Number)?.toLong() == printerId &&
              (it["queueId"] as? Number)?.toLong() == queueId
        }
        ?.let { (it["id"] as? Number)?.toLong() }
  }
  val currentJobWatchId = watchIdFor("OWN_JOB", currentQueueId)
  val nextJobWatchId = watchIdFor("OWN_JOB", nextQueueId)
  val availableWatchId = watchIdFor("PRINTER_AVAILABLE", currentQueueId)
  // Precomputed here (not with OGNL string concatenation in the template) so the client-side
  // search filter has one plain lowercase field to substring-match against.
  val searchText =
      listOfNotNull(name, model, currentJobName, nextJobName).joinToString(" ").lowercase()
  // Pre-built, already-encoded so the template never has to string-concatenate a URL itself
  // (model names like "A1 Mini" contain a space, which needs %20 - a plain path segment, not a
  // query string, so URLEncoder's "+" is converted).
  val modelIconUrl =
      model?.let { "/bambu-api/models/${URLEncoder.encode(it, "UTF-8").replace("+", "%20")}/icon" }

  return mapOf(
      // Bambuddy's printer IDs are numeric on the wire (Jackson decodes JSON numbers as Number);
      // rendered as a plain string here since the template only ever uses it in a URL/attribute.
      "id" to (printer["id"]?.let { (it as? Number)?.toLong()?.toString() ?: it.toString() } ?: ""),
      "name" to name,
      "model" to model,
      "modelIconUrl" to modelIconUrl,
      "state" to state,
      "stateLabel" to stateLabel,
      "badgeClass" to badgeClass,
      "isPrinting" to isPrinting,
      "isOffline" to isOffline,
      "isIdle" to isIdle,
      "needsAttention" to needsAttention,
      "isFinished" to isFinished,
      "filterCategory" to filterCategory,
      "searchText" to searchText,
      "progress" to progress,
      "remainingTimeDisplay" to formatRemainingTime(printer["remainingTimeMinutes"] as? Number),
      "etaDisplay" to formatEta(printer["remainingTimeMinutes"] as? Number),
      "layerDisplay" to
          if (layerNum != null && totalLayers != null) "$layerNum / $totalLayers" else "",
      "currentJobName" to currentJobName,
      "nextJobName" to nextJobName,
      "printedByUsername" to printedByUsername,
      "previousPrintedByUsername" to previousPrintedByUsername,
      "nextPrintedByUsername" to nextPrintedByUsername,
      "currentQueueId" to currentQueueId,
      "nextQueueId" to nextQueueId,
      "notificationsEnabled" to notificationsEnabled,
      "isOwnCurrentJob" to isOwnCurrentJob,
      "isOwnNextJob" to isOwnNextJob,
      "currentJobWatchId" to currentJobWatchId,
      "nextJobWatchId" to nextJobWatchId,
      "availableWatchId" to availableWatchId,
  )
}

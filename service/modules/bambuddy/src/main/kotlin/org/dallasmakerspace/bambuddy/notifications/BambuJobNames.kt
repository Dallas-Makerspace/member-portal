package org.dallasmakerspace.bambuddy.notifications

private val EXTENSIONS = listOf(".gcode.3mf", ".gcode", ".3mf")
private val WHITESPACE = Regex("\\s+")

/**
 * Comparison key for a BamBuddy job name. The webhook `filename` is the subtask name with `_`
 * turned into spaces, or the file's basename without its extension; the queue `archive_name`
 * and the printer's `current_print` keep underscores and may carry the extension. Both sides
 * go through this: basename, extension stripped, `_` as space, whitespace collapsed, lowercase.
 */
fun normalizeJobName(name: String?): String? {
  var base = name?.trim()?.substringAfterLast('/')?.substringAfterLast('\\') ?: return null
  EXTENSIONS.firstOrNull { base.endsWith(it, ignoreCase = true) }?.let {
    base = base.dropLast(it.length)
  }
  return base.replace('_', ' ').replace(WHITESPACE, " ").trim().lowercase().takeIf { it.isNotEmpty() }
}

/** Whether two BamBuddy job names refer to the same job (both must be non-blank). */
fun sameJobName(a: String?, b: String?): Boolean {
  val left = normalizeJobName(a) ?: return false
  return left == normalizeJobName(b)
}

/** Queue row statuses (live vocabulary: pending, printing, completed, failed, cancelled, skipped). */
object BambuQueueStatus {
  /** The job ran and ended; completed/failed/cancelled map to print_complete/failed/stopped. */
  val RAN_AND_ENDED = setOf("completed", "failed", "cancelled")

  /** Ended in any way, including never having run. */
  val ENDED = RAN_AND_ENDED + "skipped"
}

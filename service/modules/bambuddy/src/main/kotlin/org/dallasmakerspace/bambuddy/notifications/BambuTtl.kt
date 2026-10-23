package org.dallasmakerspace.bambuddy.notifications

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

  /** The §8.3 no-estimate rejection message (member-facing; the arm button stays for a retry). */
  const val NO_ESTIMATE_MESSAGE =
      "We can't estimate how long this print will run yet, so we can't set a reminder for it. " +
          "Try again in a moment, once printing is under way."
}

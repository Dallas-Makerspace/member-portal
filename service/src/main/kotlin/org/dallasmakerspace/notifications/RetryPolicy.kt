package org.dallasmakerspace.notifications

/** Shared retry/backoff policy for notification delivery attempts. */
object RetryPolicy {

  /**
   * Exponential backoff for the given attempt number (1-based), doubling from [baseSeconds] and
   * capped at [maxSeconds].
   */
  fun backoffSeconds(attempt: Int, baseSeconds: Long, maxSeconds: Long): Long {
    val shift = (attempt - 1).coerceAtMost(MAX_BACKOFF_SHIFT)
    return minOf(baseSeconds shl shift, maxSeconds)
  }

  private const val MAX_BACKOFF_SHIFT = 20
}

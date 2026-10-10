package org.dallasmakerspace.notifications

/** Lifecycle status of a notification (also drives worker selection). */
enum class NotificationStatus {
  /** Waiting for the worker (or immediate delivery). */
  QUEUED,

  /** Waiting for the daily/weekly digest worker. */
  IN_DIGEST,

  /** A send attempt is in flight. */
  SENDING,

  /** Accepted by the provider. */
  SENT,

  /** A transient failure occurred; the worker will retry. */
  FAILED,

  /** Permanently failed (bad recipient, retries exhausted). */
  DEAD_LETTER,

  /** Member is on the suppression list; not sent. */
  SUPPRESSED,

  /** Deliberately not sent (e.g. topic/channel disabled in preferences). */
  SKIPPED,
}

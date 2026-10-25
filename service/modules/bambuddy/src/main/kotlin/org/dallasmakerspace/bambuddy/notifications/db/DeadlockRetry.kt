package org.dallasmakerspace.bambuddy.notifications.db

import java.sql.SQLException

private const val SQLSTATE_SERIALIZATION_FAILURE = "40001"
private const val MARIADB_ER_LOCK_DEADLOCK = 1213

internal fun Throwable.isDeadlock(): Boolean =
    generateSequence(this) { it.cause }
        .any {
          it is SQLException &&
              (it.sqlState == SQLSTATE_SERIALIZATION_FAILURE ||
                  it.errorCode == MARIADB_ER_LOCK_DEADLOCK)
        }

/**
 * Re-runs [block] once if it fails with a deadlock; the loser of a gap-lock deadlock was rolled
 * back, so the whole transaction in [block] must be safe to repeat. A second failure propagates.
 */
internal suspend fun <T> retryOnceOnDeadlock(block: suspend () -> T): T =
    try {
      block()
    } catch (e: Exception) {
      if (e.isDeadlock()) block() else throw e
    }

package org.dallasmakerspace.bambuddy.notifications.db

import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton
import org.dallasmakerspace.bambuddy.notifications.PendingOfflineStatus
import org.dallasmakerspace.bambuddy.notifications.WatchStatus
import org.dallasmakerspace.core.db.suspendTransaction
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.min
import org.jetbrains.exposed.sql.select

/** Read side of the scheduler: when is the next stored deadline (one transaction, two MINs). */
@Singleton
open class BambuScheduleRepository @Inject constructor() {

  /** Earliest of the ARMED watch expiries and the PENDING offline releases; null when none. */
  open suspend fun nextDue(): LocalDateTime? = suspendTransaction {
    val minExpiry = EphemeralNotificationsTable.expiresAt.min()
    val minRelease = PendingOfflineAlertsTable.releaseAt.min()
    val expiry =
        EphemeralNotificationsTable.slice(minExpiry)
            .select { EphemeralNotificationsTable.status eq WatchStatus.ARMED.name }
            .firstOrNull()
            ?.get(minExpiry)
    val release =
        PendingOfflineAlertsTable.slice(minRelease)
            .select { PendingOfflineAlertsTable.status eq PendingOfflineStatus.PENDING.name }
            .firstOrNull()
            ?.get(minRelease)
    listOfNotNull(expiry, release).minOrNull()
  }
}

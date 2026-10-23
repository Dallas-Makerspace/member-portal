package org.dallasmakerspace.bambuddy.notifications.db

import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton
import org.dallasmakerspace.bambuddy.notifications.PendingOfflineAlert
import org.dallasmakerspace.bambuddy.notifications.PendingOfflineStatus
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.core.db.suspendTransaction
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update

/** CRUD + resolution claims for `bambu_pending_offline_alerts` (one row per offline episode). */
@Singleton
open class PendingOfflineAlertsRepository @Inject constructor(loggerFactory: LoggerFactory) {

  open suspend fun insert(alert: PendingOfflineAlert): PendingOfflineAlert {
    val id = suspendTransaction {
      val statement =
          PendingOfflineAlertsTable.insert {
            it[printerId] = alert.printerId
            it[printerName] = alert.printerName
            it[offlineSince] = alert.offlineSince
            it[releaseAt] = alert.releaseAt
            it[status] = alert.status.name
            it[resolvedAt] = alert.resolvedAt
            it[notificationsCreated] = alert.notificationsCreated
          }
      requireNotNull(statement[PendingOfflineAlertsTable.idColumn]) { "Generated key missing" }.value
    }
    return alert.copy(id = id)
  }

  /**
   * Atomic insert-if-absent for a new offline episode: in ONE transaction, if an episode for
   * this printer is already PENDING (or being released, PROCESSING) returns null and inserts
   * nothing; otherwise inserts [alert] and returns it. Terminal rows (SENT/SUPPRESSED) never
   * block a later episode. The existence check is a locking read inside the same transaction
   * as the insert, so concurrent duplicate `printer_offline` webhooks cannot both insert —
   * there is deliberately no unique key on printer_id, which would break later episodes.
   */
  open suspend fun insertIfNoPending(alert: PendingOfflineAlert): PendingOfflineAlert? =
      suspendTransaction {
        val existing =
            PendingOfflineAlertsTable.select {
                  (PendingOfflineAlertsTable.printerId eq alert.printerId) and
                      (PendingOfflineAlertsTable.status inList
                          listOf(
                              PendingOfflineStatus.PENDING.name,
                              PendingOfflineStatus.PROCESSING.name))
                }
                .forUpdate()
                .firstOrNull()
        if (existing != null) {
          return@suspendTransaction null
        }
        val statement =
            PendingOfflineAlertsTable.insert {
              it[printerId] = alert.printerId
              it[printerName] = alert.printerName
              it[offlineSince] = alert.offlineSince
              it[releaseAt] = alert.releaseAt
              it[status] = alert.status.name
              it[resolvedAt] = alert.resolvedAt
              it[notificationsCreated] = alert.notificationsCreated
            }
        val id =
            requireNotNull(statement[PendingOfflineAlertsTable.idColumn]) {
                  "Generated key missing"
                }
                .value
        alert.copy(id = id)
      }

  /** The PENDING row for a printer, if an episode is already being held. */
  open suspend fun findPendingByPrinter(printerId: Int): PendingOfflineAlert? = suspendTransaction {
    PendingOfflineAlertsTable.select {
          (PendingOfflineAlertsTable.printerId eq printerId) and
              (PendingOfflineAlertsTable.status eq PendingOfflineStatus.PENDING.name)
        }
        .firstOrNull()
        ?.let(::toAlert)
  }

  /** PENDING rows whose hold has elapsed, oldest release first, at most [limit]. */
  open suspend fun findDuePending(now: LocalDateTime, limit: Int): List<PendingOfflineAlert> =
      suspendTransaction {
        PendingOfflineAlertsTable.select {
              (PendingOfflineAlertsTable.status eq PendingOfflineStatus.PENDING.name) and
                  (PendingOfflineAlertsTable.releaseAt lessEq now)
            }
            .orderBy(PendingOfflineAlertsTable.releaseAt to SortOrder.ASC)
            .limit(limit)
            .map(::toAlert)
      }

  /**
   * Claims an episode for release processing: PENDING → PROCESSING. Only the claim winner may
   * re-check and fan out, so overlapping sweeper instances cannot both email subscribers for a
   * suppressed-or-sent episode. True iff this caller won.
   */
  open suspend fun claimProcessing(id: Long): Boolean = suspendTransaction {
    PendingOfflineAlertsTable.update({
      (PendingOfflineAlertsTable.idColumn eq id) and
          (PendingOfflineAlertsTable.status eq PendingOfflineStatus.PENDING.name)
    }) {
      it[status] = PendingOfflineStatus.PROCESSING.name
    } > 0
  }

  /** Returns a PROCESSING claim to PENDING after a failed release attempt, for the next pass. */
  open suspend fun releaseProcessingClaim(id: Long): Boolean = suspendTransaction {
    PendingOfflineAlertsTable.update({
      (PendingOfflineAlertsTable.idColumn eq id) and
          (PendingOfflineAlertsTable.status eq PendingOfflineStatus.PROCESSING.name)
    }) {
      it[status] = PendingOfflineStatus.PENDING.name
    } > 0
  }

  /** Claims resolution as SUPPRESSED (printer back online); from PENDING or PROCESSING. */
  open suspend fun claimSuppressed(id: Long, resolvedAt: LocalDateTime): Boolean =
      suspendTransaction {
        PendingOfflineAlertsTable.update({
          (PendingOfflineAlertsTable.idColumn eq id) and
              (PendingOfflineAlertsTable.status inList
                  listOf(
                      PendingOfflineStatus.PENDING.name, PendingOfflineStatus.PROCESSING.name))
        }) {
          it[status] = PendingOfflineStatus.SUPPRESSED.name
          it[this.resolvedAt] = resolvedAt
        } > 0
      }

  /** Claims resolution as SENT after the fan-out; from PENDING or PROCESSING. */
  open suspend fun claimSent(id: Long, resolvedAt: LocalDateTime, notificationsCreated: Int): Boolean =
      suspendTransaction {
        PendingOfflineAlertsTable.update({
          (PendingOfflineAlertsTable.idColumn eq id) and
              (PendingOfflineAlertsTable.status inList
                  listOf(
                      PendingOfflineStatus.PENDING.name, PendingOfflineStatus.PROCESSING.name))
        }) {
          it[status] = PendingOfflineStatus.SENT.name
          it[this.resolvedAt] = resolvedAt
          it[this.notificationsCreated] = notificationsCreated
        } > 0
      }

  /** Hard-deletes resolved rows older than [cutoff] (by resolved_at). */
  open suspend fun purgeResolved(cutoff: LocalDateTime): Int = suspendTransaction {
    PendingOfflineAlertsTable.deleteWhere {
      (status inList listOf(PendingOfflineStatus.SENT.name, PendingOfflineStatus.SUPPRESSED.name)) and
          (resolvedAt less cutoff)
    }
  }

  private fun toAlert(row: ResultRow): PendingOfflineAlert =
      PendingOfflineAlert(
          id = row[PendingOfflineAlertsTable.idColumn].value,
          printerId = row[PendingOfflineAlertsTable.printerId],
          printerName = row[PendingOfflineAlertsTable.printerName],
          offlineSince = row[PendingOfflineAlertsTable.offlineSince],
          releaseAt = row[PendingOfflineAlertsTable.releaseAt],
          status = PendingOfflineStatus.valueOf(row[PendingOfflineAlertsTable.status]),
          resolvedAt = row[PendingOfflineAlertsTable.resolvedAt],
          notificationsCreated = row[PendingOfflineAlertsTable.notificationsCreated],
      )
}

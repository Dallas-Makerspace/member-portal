package org.dallasmakerspace.bambuddy.notifications.db

import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton
import org.dallasmakerspace.bambuddy.notifications.EphemeralNotification
import org.dallasmakerspace.bambuddy.notifications.WatchScope
import org.dallasmakerspace.bambuddy.notifications.WatchStatus
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.core.db.suspendTransaction
import org.dallasmakerspace.members.db.ProfileResolver
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

/**
 * CRUD + atomic claims for `bambu_ephemeral_notifications`. Rows are keyed by profile id; this
 * repository is the boundary that translates to/from member usernames (pattern:
 * NotificationsRepository). Every state transition is a single conditional UPDATE guarded on the
 * expected prior state (design §9) — losing a claim is a normal race outcome, never an error.
 */
@Singleton
@Suppress("TooManyFunctions")
open class EphemeralNotificationsRepository @Inject constructor(loggerFactory: LoggerFactory) {
  private val log = loggerFactory.create(javaClass)

  open suspend fun insert(watch: EphemeralNotification): EphemeralNotification {
    val id = suspendTransaction {
      val profileId = ProfileResolver.requireProfileId(watch.memberUsername)
      val statement =
          EphemeralNotificationsTable.insert {
            it[this.profileId] = profileId
            it[scope] = watch.scope.name
            it[printerId] = watch.printerId
            it[printerName] = watch.printerName
            it[queueId] = watch.queueId
            it[filename] = watch.filename
            it[jobName] = watch.jobName
            it[triggerEvents] = watch.triggerEvents.joinToString(",")
            it[status] = watch.status.name
            it[rearmCount] = watch.rearmCount
            it[createdAt] = watch.createdAt
            it[expiresAt] = watch.expiresAt
            it[terminalAt] = watch.terminalAt
            it[firedNotificationId] = watch.firedNotificationId
            it[terminalReason] = watch.terminalReason
          }
      requireNotNull(statement[EphemeralNotificationsTable.idColumn]) { "Generated key missing" }
          .value
    }
    return watch.copy(id = id)
  }

  /**
   * Atomic arm: in ONE transaction, returns the existing ARMED watch matching [watch]'s member +
   * scope + printer + queue if there is one (idempotent re-click), otherwise inserts [watch].
   * The existence check is a locking read (`FOR UPDATE`) inside the same transaction as the
   * insert, so two concurrent arms of the same print cannot both insert — the second sees the
   * first's row once it commits (or blocks on the locked index range until it does). Returns the
   * stored watch and whether this call created it. A deadlock victim is retried once.
   */
  open suspend fun insertArmedIfAbsent(watch: EphemeralNotification): Pair<EphemeralNotification, Boolean> =
      retryOnceOnDeadlock { insertArmedIfAbsentOnce(watch) }

  private suspend fun insertArmedIfAbsentOnce(
      watch: EphemeralNotification
  ): Pair<EphemeralNotification, Boolean> =
      suspendTransaction {
        val profileId = ProfileResolver.requireProfileId(watch.memberUsername)
        val existing =
            EphemeralNotificationsTable.select {
                  (EphemeralNotificationsTable.profileId eq profileId) and
                      (EphemeralNotificationsTable.scope eq watch.scope.name) and
                      (EphemeralNotificationsTable.printerId eq watch.printerId) and
                      (EphemeralNotificationsTable.queueId eq watch.queueId) and
                      (EphemeralNotificationsTable.status eq WatchStatus.ARMED.name)
                }
                .forUpdate()
                .firstOrNull()
        if (existing != null) {
          return@suspendTransaction toWatches(listOf(existing)).first() to false
        }
        val statement =
            EphemeralNotificationsTable.insert {
              it[this.profileId] = profileId
              it[scope] = watch.scope.name
              it[printerId] = watch.printerId
              it[printerName] = watch.printerName
              it[queueId] = watch.queueId
              it[filename] = watch.filename
              it[jobName] = watch.jobName
              it[triggerEvents] = watch.triggerEvents.joinToString(",")
              it[status] = watch.status.name
              it[rearmCount] = watch.rearmCount
              it[createdAt] = watch.createdAt
              it[expiresAt] = watch.expiresAt
              it[terminalAt] = watch.terminalAt
              it[firedNotificationId] = watch.firedNotificationId
              it[terminalReason] = watch.terminalReason
            }
        val id =
            requireNotNull(statement[EphemeralNotificationsTable.idColumn]) {
                  "Generated key missing"
                }
                .value
        watch.copy(id = id) to true
      }

  open suspend fun getById(id: Long): EphemeralNotification? = suspendTransaction {
    val row =
        EphemeralNotificationsTable.select { EphemeralNotificationsTable.idColumn eq id }
            .firstOrNull()
            ?: return@suspendTransaction null
    toWatches(listOf(row)).firstOrNull()
  }

  /** The member's ARMED watches, oldest first. */
  open suspend fun listArmed(memberUsername: String): List<EphemeralNotification> =
      suspendTransaction {
        val profileId =
            ProfileResolver.findProfileId(memberUsername) ?: return@suspendTransaction emptyList()
        val rows =
            EphemeralNotificationsTable.select {
                  (EphemeralNotificationsTable.profileId eq profileId) and
                      (EphemeralNotificationsTable.status eq WatchStatus.ARMED.name)
                }
                .orderBy(EphemeralNotificationsTable.idColumn to SortOrder.ASC)
                .toList()
        toWatches(rows)
      }

  /** All ARMED watches on a printer (webhook correlation candidates). */
  open suspend fun findArmedByPrinter(printerId: Int): List<EphemeralNotification> =
      suspendTransaction {
        val rows =
            EphemeralNotificationsTable.select {
                  (EphemeralNotificationsTable.printerId eq printerId) and
                      (EphemeralNotificationsTable.status eq WatchStatus.ARMED.name)
                }
                .orderBy(EphemeralNotificationsTable.idColumn to SortOrder.ASC)
                .toList()
        toWatches(rows)
      }

  /** All ARMED watches on a printer by its name snapshot (unverified-correlation fallback). */
  open suspend fun findArmedByPrinterName(printerName: String): List<EphemeralNotification> =
      suspendTransaction {
        val rows =
            EphemeralNotificationsTable.select {
                  (EphemeralNotificationsTable.printerName eq printerName) and
                      (EphemeralNotificationsTable.status eq WatchStatus.ARMED.name)
                }
                .orderBy(EphemeralNotificationsTable.idColumn to SortOrder.ASC)
                .toList()
        toWatches(rows)
      }

  /** ARMED watches whose TTL deadline has passed, oldest expiry first, at most [limit]. */
  open suspend fun findDueArmed(now: LocalDateTime, limit: Int): List<EphemeralNotification> =
      suspendTransaction {
        val rows =
            EphemeralNotificationsTable.select {
                  (EphemeralNotificationsTable.status eq WatchStatus.ARMED.name) and
                      (EphemeralNotificationsTable.expiresAt lessEq now)
                }
                .orderBy(EphemeralNotificationsTable.expiresAt to SortOrder.ASC)
                .limit(limit)
                .toList()
        toWatches(rows)
      }

  /** Claims the watch for firing: ARMED → FIRED. True iff this caller won the claim. */
  open suspend fun claimForFiring(id: Long, now: LocalDateTime, reason: String): Boolean =
      suspendTransaction {
        EphemeralNotificationsTable.update({
          (EphemeralNotificationsTable.idColumn eq id) and
              (EphemeralNotificationsTable.status eq WatchStatus.ARMED.name)
        }) {
          it[status] = WatchStatus.FIRED.name
          it[terminalAt] = now
          it[terminalReason] = reason
        } > 0
      }

  /** Claims the watch for expiry: ARMED → EXPIRED. True iff this caller won the claim. */
  open suspend fun claimForExpiry(id: Long, now: LocalDateTime, reason: String): Boolean =
      suspendTransaction {
        EphemeralNotificationsTable.update({
          (EphemeralNotificationsTable.idColumn eq id) and
              (EphemeralNotificationsTable.status eq WatchStatus.ARMED.name)
        }) {
          it[status] = WatchStatus.EXPIRED.name
          it[terminalAt] = now
          it[terminalReason] = reason
        } > 0
      }

  /**
   * Re-arms the watch exactly once: stays ARMED, rearm_count 0 → 1, new deadline. When the
   * blocking job changed (printer-available watches), the stored queue/filename/job snapshot is
   * moved to the new blocking job so a later webhook can still correlate.
   */
  open suspend fun claimForRearm(
      id: Long,
      newExpiresAt: LocalDateTime,
      newQueueId: Int? = null,
      newFilename: String? = null,
      newJobName: String? = null,
  ): Boolean = suspendTransaction {
    EphemeralNotificationsTable.update({
      (EphemeralNotificationsTable.idColumn eq id) and
          (EphemeralNotificationsTable.status eq WatchStatus.ARMED.name) and
          (EphemeralNotificationsTable.rearmCount eq 0)
    }) {
      it[expiresAt] = newExpiresAt
      it[rearmCount] = 1
      if (newQueueId != null) {
        it[queueId] = newQueueId
        it[filename] = newFilename
        it[jobName] = newJobName
      }
    } > 0
  }

  /**
   * Moves an ARMED watch to another job with a new deadline without touching rearm_count (used
   * when a printer-available watch finds more queued work). Guarded on the job it still points
   * at, so a concurrent retarget of the same watch loses cleanly.
   */
  @Suppress("LongParameterList")
  open suspend fun claimForRetarget(
      id: Long,
      expectedQueueId: Int?,
      newExpiresAt: LocalDateTime,
      newQueueId: Int,
      newFilename: String?,
      newJobName: String?,
  ): Boolean = suspendTransaction {
    EphemeralNotificationsTable.update({
      (EphemeralNotificationsTable.idColumn eq id) and
          (EphemeralNotificationsTable.status eq WatchStatus.ARMED.name) and
          (if (expectedQueueId == null) EphemeralNotificationsTable.queueId.isNull()
          else EphemeralNotificationsTable.queueId eq expectedQueueId)
    }) {
      it[expiresAt] = newExpiresAt
      it[queueId] = newQueueId
      it[filename] = newFilename
      it[jobName] = newJobName
    } > 0
  }

  /** Cancels the watch, scoped to its owner: ARMED → CANCELLED. True iff this caller won. */
  open suspend fun claimForCancel(id: Long, memberUsername: String, now: LocalDateTime): Boolean =
      suspendTransaction {
        val profileId =
            ProfileResolver.findProfileId(memberUsername) ?: return@suspendTransaction false
        EphemeralNotificationsTable.update({
          (EphemeralNotificationsTable.idColumn eq id) and
              (EphemeralNotificationsTable.profileId eq profileId) and
              (EphemeralNotificationsTable.status eq WatchStatus.ARMED.name)
        }) {
          it[status] = WatchStatus.CANCELLED.name
          it[terminalAt] = now
          it[terminalReason] = "Cancelled by member"
        } > 0
      }

  /** Records the notification a fire/expiry produced. */
  open suspend fun setFiredNotificationId(id: Long, notificationId: Long) {
    suspendTransaction {
      EphemeralNotificationsTable.update({ EphemeralNotificationsTable.idColumn eq id }) {
        it[firedNotificationId] = notificationId
      }
    }
  }

  /**
   * Terminal tombstones (FIRED/EXPIRED) that never got their notification linked — the gap a
   * crash between the (separately committed) claim and the notification create leaves behind.
   * The reconciliation pass in EphemeralNotificationService recreates their notices.
   */
  open suspend fun findTerminalWithoutNotification(limit: Int): List<EphemeralNotification> =
      suspendTransaction {
        val rows =
            EphemeralNotificationsTable.select {
                  (EphemeralNotificationsTable.status inList
                      listOf(WatchStatus.FIRED.name, WatchStatus.EXPIRED.name)) and
                      (EphemeralNotificationsTable.firedNotificationId.isNull())
                }
                .orderBy(EphemeralNotificationsTable.idColumn to SortOrder.ASC)
                .limit(limit)
                .toList()
        toWatches(rows)
      }

  /**
   * Compensation for a failed notification create: a terminal claim whose notice was never
   * created (fired_notification_id still NULL) returns to ARMED, so the watch is not stranded
   * as a tombstone with nothing sent. True iff this caller reverted the claim.
   */
  open suspend fun revertTerminalClaim(id: Long): Boolean = suspendTransaction {
    EphemeralNotificationsTable.update({
      (EphemeralNotificationsTable.idColumn eq id) and
          (EphemeralNotificationsTable.status inList
              listOf(WatchStatus.FIRED.name, WatchStatus.EXPIRED.name)) and
          EphemeralNotificationsTable.firedNotificationId.isNull()
    }) {
      it[status] = WatchStatus.ARMED.name
      it[terminalAt] = null
      it[terminalReason] = null
    } > 0
  }

  /**
   * Compensation for a failed overdue-notice create after a successful re-arm claim: restores
   * rearm_count = 0 and the watch's previous deadline / queue snapshot.
   */
  open suspend fun revertRearm(
      id: Long,
      previousExpiresAt: LocalDateTime,
      previousQueueId: Int?,
      previousFilename: String?,
      previousJobName: String?,
  ): Boolean = suspendTransaction {
    EphemeralNotificationsTable.update({
      (EphemeralNotificationsTable.idColumn eq id) and
          (EphemeralNotificationsTable.status eq WatchStatus.ARMED.name) and
          (EphemeralNotificationsTable.rearmCount eq 1)
    }) {
      it[expiresAt] = previousExpiresAt
      it[rearmCount] = 0
      it[queueId] = previousQueueId
      it[filename] = previousFilename
      it[jobName] = previousJobName
    } > 0
  }

  /** Hard-deletes terminal tombstones whose terminal_at is older than [cutoff]. */
  open suspend fun purgeTombstones(cutoff: LocalDateTime): Int = suspendTransaction {
    EphemeralNotificationsTable.deleteWhere {
      (status inList
          listOf(WatchStatus.FIRED.name, WatchStatus.EXPIRED.name, WatchStatus.CANCELLED.name)) and
          (terminalAt less cutoff)
    }
  }

  private fun toWatches(rows: List<ResultRow>): List<EphemeralNotification> {
    val usernames = ProfileResolver.resolveUsernames(rows.map { it[EphemeralNotificationsTable.profileId] })
    return rows.mapNotNull { row ->
      val profileId = row[EphemeralNotificationsTable.profileId]
      val username = usernames[profileId]
      if (username == null) {
        log.warn(
            "Watch ${row[EphemeralNotificationsTable.idColumn].value} references unknown profile_id $profileId, skipping")
        null
      } else {
        toWatch(row, username)
      }
    }
  }

  private fun toWatch(row: ResultRow, memberUsername: String): EphemeralNotification =
      EphemeralNotification(
          id = row[EphemeralNotificationsTable.idColumn].value,
          memberUsername = memberUsername,
          scope = WatchScope.valueOf(row[EphemeralNotificationsTable.scope]),
          printerId = row[EphemeralNotificationsTable.printerId],
          printerName = row[EphemeralNotificationsTable.printerName],
          queueId = row[EphemeralNotificationsTable.queueId],
          filename = row[EphemeralNotificationsTable.filename],
          jobName = row[EphemeralNotificationsTable.jobName],
          triggerEvents =
              row[EphemeralNotificationsTable.triggerEvents]
                  .split(",")
                  .map { it.trim() }
                  .filter { it.isNotEmpty() },
          status = WatchStatus.valueOf(row[EphemeralNotificationsTable.status]),
          rearmCount = row[EphemeralNotificationsTable.rearmCount],
          createdAt = row[EphemeralNotificationsTable.createdAt],
          expiresAt = row[EphemeralNotificationsTable.expiresAt],
          terminalAt = row[EphemeralNotificationsTable.terminalAt],
          firedNotificationId = row[EphemeralNotificationsTable.firedNotificationId],
          terminalReason = row[EphemeralNotificationsTable.terminalReason],
      )
}

package org.dallasmakerspace.bambuddy.notifications

import java.time.LocalDateTime
import org.dallasmakerspace.bambuddy.notifications.db.EphemeralNotificationsRepository
import org.dallasmakerspace.core.LoggerFactory

/**
 * In-memory stand-in for [EphemeralNotificationsRepository] that reproduces the real repository's
 * conditional-claim semantics (a claim succeeds only from the expected prior state; re-arm only
 * from rearm_count = 0) so the service state machine can be tested without a database. The real
 * repository's SQL claims are covered against H2 in BambuRepositoriesTest.
 */
class FakeEphemeralNotificationsRepository(loggerFactory: LoggerFactory) :
    EphemeralNotificationsRepository(loggerFactory) {

  val watches = mutableListOf<EphemeralNotification>()
  private var nextId = 1L

  private fun replace(watch: EphemeralNotification) {
    val idx = watches.indexOfFirst { it.id == watch.id }
    if (idx >= 0) watches[idx] = watch
  }

  override suspend fun insert(watch: EphemeralNotification): EphemeralNotification {
    val stored = watch.copy(id = nextId++)
    watches += stored
    return stored
  }

  override suspend fun insertArmedIfAbsent(
      watch: EphemeralNotification
  ): Pair<EphemeralNotification, Boolean> {
    watches.firstOrNull {
      it.memberUsername == watch.memberUsername &&
          it.scope == watch.scope &&
          it.printerId == watch.printerId &&
          it.queueId == watch.queueId &&
          it.status == WatchStatus.ARMED
    }?.let {
      return it to false
    }
    return insert(watch) to true
  }

  override suspend fun findTerminalWithoutNotification(
      limit: Int
  ): List<EphemeralNotification> =
      watches
          .filter {
            (it.status == WatchStatus.FIRED || it.status == WatchStatus.EXPIRED) &&
                it.firedNotificationId == null
          }
          .take(limit)

  override suspend fun revertTerminalClaim(id: Long): Boolean {
    val watch = getById(id) ?: return false
    if ((watch.status != WatchStatus.FIRED && watch.status != WatchStatus.EXPIRED) ||
        watch.firedNotificationId != null) {
      return false
    }
    replace(watch.copy(status = WatchStatus.ARMED, terminalAt = null, terminalReason = null))
    return true
  }

  override suspend fun revertRearm(
      id: Long,
      previousExpiresAt: LocalDateTime,
      previousQueueId: Int?,
      previousFilename: String?,
      previousJobName: String?,
  ): Boolean {
    val watch = getById(id) ?: return false
    if (watch.status != WatchStatus.ARMED || watch.rearmCount != 1) return false
    replace(
        watch.copy(
            expiresAt = previousExpiresAt,
            rearmCount = 0,
            queueId = previousQueueId,
            filename = previousFilename,
            jobName = previousJobName))
    return true
  }

  override suspend fun getById(id: Long): EphemeralNotification? =
      watches.firstOrNull { it.id == id }

  override suspend fun listArmed(memberUsername: String): List<EphemeralNotification> =
      watches.filter { it.memberUsername == memberUsername && it.status == WatchStatus.ARMED }

  override suspend fun findArmedByPrinter(printerId: Int): List<EphemeralNotification> =
      watches.filter { it.printerId == printerId && it.status == WatchStatus.ARMED }

  override suspend fun findArmedByPrinterName(printerName: String): List<EphemeralNotification> =
      watches.filter { it.printerName == printerName && it.status == WatchStatus.ARMED }

  override suspend fun findDueArmed(now: LocalDateTime, limit: Int): List<EphemeralNotification> =
      watches
          .filter { it.status == WatchStatus.ARMED && !it.expiresAt.isAfter(now) }
          .sortedBy { it.expiresAt }
          .take(limit)

  override suspend fun claimForFiring(id: Long, now: LocalDateTime, reason: String): Boolean {
    val watch = getById(id) ?: return false
    if (watch.status != WatchStatus.ARMED) return false
    replace(watch.copy(status = WatchStatus.FIRED, terminalAt = now, terminalReason = reason))
    return true
  }

  override suspend fun claimForExpiry(id: Long, now: LocalDateTime, reason: String): Boolean {
    val watch = getById(id) ?: return false
    if (watch.status != WatchStatus.ARMED) return false
    replace(watch.copy(status = WatchStatus.EXPIRED, terminalAt = now, terminalReason = reason))
    return true
  }

  override suspend fun claimForRearm(
      id: Long,
      newExpiresAt: LocalDateTime,
      newQueueId: Int?,
      newFilename: String?,
      newJobName: String?,
  ): Boolean {
    val watch = getById(id) ?: return false
    if (watch.status != WatchStatus.ARMED || watch.rearmCount != 0) return false
    replace(
        watch.copy(
            expiresAt = newExpiresAt,
            rearmCount = 1,
            queueId = newQueueId ?: watch.queueId,
            filename = if (newQueueId != null) newFilename else watch.filename,
            jobName = if (newQueueId != null) newJobName else watch.jobName,
        ))
    return true
  }

  override suspend fun claimForRetarget(
      id: Long,
      expectedQueueId: Int?,
      newExpiresAt: LocalDateTime,
      newQueueId: Int,
      newFilename: String?,
      newJobName: String?,
  ): Boolean {
    val watch = getById(id) ?: return false
    if (watch.status != WatchStatus.ARMED || watch.queueId != expectedQueueId) return false
    replace(
        watch.copy(
            expiresAt = newExpiresAt,
            queueId = newQueueId,
            filename = newFilename,
            jobName = newJobName))
    return true
  }

  override suspend fun claimForCancel(
      id: Long,
      memberUsername: String,
      now: LocalDateTime,
  ): Boolean {
    val watch = getById(id) ?: return false
    if (watch.status != WatchStatus.ARMED || watch.memberUsername != memberUsername) return false
    replace(watch.copy(status = WatchStatus.CANCELLED, terminalAt = now))
    return true
  }

  override suspend fun setFiredNotificationId(id: Long, notificationId: Long) {
    getById(id)?.let { replace(it.copy(firedNotificationId = notificationId)) }
  }

  override suspend fun purgeTombstones(cutoff: LocalDateTime): Int {
    val doomed =
        watches.filter {
          it.status != WatchStatus.ARMED &&
              it.terminalAt != null &&
              it.terminalAt!!.isBefore(cutoff)
        }
    watches.removeAll(doomed)
    return doomed.size
  }
}

package org.dallasmakerspace.smartwaiver

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import org.dallasmakerspace.core.DBMasterConnection
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update

private val DATETIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
private const val QUEUE_LOOKBACK_DAYS = 30L

/**
 * The original, minimal SmartWaiver storage (one thin row per waiver) and the webhook queue. The
 * full mirror is [SmartWaiverMirrorRepository]; this path stays until `smartwaiver.mirror.enabled`
 * is on everywhere.
 */
@Singleton
class SmartWaiverRepository @Inject constructor() {

  private fun doInsert(waiver: SmartwaiverFullWaiver) {
    val referral = referralFrom(waiver)
    SmartWaiverTable.insert {
      it[firstName] = waiver.firstName.orEmpty()
      it[lastName] = waiver.lastName.orEmpty()
      it[email] = waiver.email
      it[birthDate] = waiver.dob.orEmpty()
      it[dateCompleted] = waiver.createdOn.orEmpty()
      it[waiverId] = waiver.waiverId
      it[waiverStatus] = "Completed At Kiosk"
      it[documentTitle] = waiver.title
      it[checkinCount] = 1
      it[referralType] = referral.type
      it[referralSource] = referral.source
      it[requestedWaiverEmail] = 1
      it[guardianFirstName] = ""
      it[guardianLastName] = ""
    }
  }

  suspend fun insertWebhookQueueEntry(uniqueId: String, event: String): Boolean =
      newSuspendedTransaction(Dispatchers.IO, db = DBMasterConnection.db) {
        val alreadyQueued =
            SmartWaiverHooksTable.select {
                  (SmartWaiverHooksTable.uniqueId eq uniqueId) and
                      (SmartWaiverHooksTable.hook eq event) and
                      SmartWaiverHooksTable.processedAt.isNull()
                }
                .limit(1)
                .any()
        if (alreadyQueued) return@newSuspendedTransaction false
        SmartWaiverHooksTable.insert {
          it[SmartWaiverHooksTable.uniqueId] = uniqueId
          it[hook] = event
          it[createdAt] = LocalDateTime.now().format(DATETIME_FORMAT)
        }
        true
      }

  suspend fun getUnprocessedQueueEntries(): List<Triple<Int, String, String>> {
    val cutoff = LocalDateTime.now().minusDays(QUEUE_LOOKBACK_DAYS).format(DATETIME_FORMAT)
    val result = mutableListOf<Triple<Int, String, String>>()
    newSuspendedTransaction(Dispatchers.IO, db = DBMasterConnection.db) {
      exec(
          "SELECT id, unique_id, hook FROM `dms-makermanager`.smartwaiver_hooks " +
              "WHERE processed_at IS NULL AND created_at >= '$cutoff'") { rs ->
            while (rs.next()) {
              result.add(Triple(rs.getInt("id"), rs.getString("unique_id"), rs.getString("hook")))
            }
          }
    }
    return result
  }

  suspend fun insertWaiverAndMarkProcessed(waiver: SmartwaiverFullWaiver, queueId: Int) =
      newSuspendedTransaction(Dispatchers.IO, db = DBMasterConnection.db) {
        val exists =
            SmartWaiverTable.select { SmartWaiverTable.waiverId eq waiver.waiverId }
                .firstOrNull() != null
        if (!exists) doInsert(waiver)
        val now = LocalDateTime.now().format(DATETIME_FORMAT)
        SmartWaiverHooksTable.update({ SmartWaiverHooksTable.id eq queueId }) {
          it[processedAt] = now
          it[updatedAt] = now
        }
      }
}

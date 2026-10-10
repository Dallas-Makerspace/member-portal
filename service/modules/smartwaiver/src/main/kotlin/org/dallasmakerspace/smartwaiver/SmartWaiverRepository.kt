package org.dallasmakerspace.smartwaiver

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import org.dallasmakerspace.core.DBMasterConnection
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update

private val DATETIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
private const val QUEUE_LOOKBACK_DAYS = 30L

@Singleton
class SmartWaiverRepository @Inject constructor() {

  private fun doInsert(waiver: SmartwaiverFullWaiver) {
    val customFields = waiver.customWaiverFields.values.toList()
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
      it[referralType] = customFields.getOrNull(0)?.value
      it[referralSource] = customFields.getOrNull(1)?.value
      it[requestedWaiverEmail] = 1
      it[guardianFirstName] = ""
      it[guardianLastName] = ""
    }
  }

  suspend fun insertSmartWaiver(waiver: SmartwaiverFullWaiver) =
      newSuspendedTransaction(Dispatchers.IO, db = DBMasterConnection.db) { doInsert(waiver) }

  suspend fun insertSmartWaiverBatch(waivers: List<SmartwaiverFullWaiver>) =
      newSuspendedTransaction(Dispatchers.IO, db = DBMasterConnection.db) {
        waivers.forEach { doInsert(it) }
      }

  // Batch existence check — one IN query instead of N individual SELECTs
  suspend fun findExistingWaiverIds(waiverIds: List<String>): Set<String> {
    if (waiverIds.isEmpty()) return emptySet()
    return newSuspendedTransaction(Dispatchers.IO, db = DBMasterConnection.db) {
      SmartWaiverTable.slice(SmartWaiverTable.waiverId)
          .select { SmartWaiverTable.waiverId inList waiverIds }
          .map { it[SmartWaiverTable.waiverId] }
          .toSet()
    }
  }

  // Insert from summary data — skips getWaiver API calls; customWaiverFields will be null
  suspend fun insertSmartWaiverBatchFromSummaries(summaries: List<SmartwaiverSummary>) =
      newSuspendedTransaction(Dispatchers.IO, db = DBMasterConnection.db) {
        summaries.forEach { s ->
          SmartWaiverTable.insert {
            it[firstName] = s.firstName.orEmpty()
            it[lastName] = s.lastName.orEmpty()
            it[email] = s.email
            it[birthDate] = s.dob.orEmpty()
            it[dateCompleted] = s.createdOn
            it[waiverId] = s.waiverId
            it[waiverStatus] = "Completed At Kiosk"
            it[documentTitle] = s.title
            it[checkinCount] = 1
            it[referralType] = null
            it[referralSource] = null
            it[requestedWaiverEmail] = 1
            it[guardianFirstName] = ""
            it[guardianLastName] = ""
          }
        }
      }

  suspend fun existsByWaiverId(waiverId: String): Boolean =
      newSuspendedTransaction(Dispatchers.IO, db = DBMasterConnection.db) {
        SmartWaiverTable.select { SmartWaiverTable.waiverId eq waiverId }.firstOrNull() != null
      }

  suspend fun insertWebhookQueueEntry(uniqueId: String, event: String): Boolean {
    var alreadyQueued = false
    newSuspendedTransaction(Dispatchers.IO, db = DBMasterConnection.db) {
      exec(
          "SELECT COUNT(*) FROM `dms-makermanager`.smartwaiver_hooks " +
              "WHERE unique_id = '$uniqueId' AND processed_at IS NULL") { rs ->
            if (rs.next()) alreadyQueued = rs.getInt(1) > 0
          }
    }
    if (alreadyQueued) return false
    newSuspendedTransaction(Dispatchers.IO, db = DBMasterConnection.db) {
      SmartWaiverHooksTable.insert {
        it[SmartWaiverHooksTable.uniqueId] = uniqueId
        it[hook] = event
        it[createdAt] = LocalDateTime.now().format(DATETIME_FORMAT)
      }
    }
    return true
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

  suspend fun getMaxDateCompleted(): LocalDate? {
    var maxDate: LocalDate? = null
    newSuspendedTransaction(Dispatchers.IO, db = DBMasterConnection.db) {
      exec("SELECT MAX(dateCompleted) FROM `dms-makermanager`.smartwaiver_data") { rs ->
        if (rs.next()) maxDate = rs.getString(1)?.take(10)?.let { LocalDate.parse(it) }
      }
    }
    return maxDate
  }
}

package org.dallasmakerspace.smartwaiver

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import org.dallasmakerspace.core.DBMasterConnection
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.max
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.UpdateBuilder
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update

private const val NAME_WIDTH = 100
private const val TITLE_WIDTH = 100
private const val EMAIL_WIDTH = 100
private const val ADDRESS_WIDTH = 255
private const val SHORT_WIDTH = 50
private const val ZIP_WIDTH = 20
private const val PHONE_WIDTH = 50
private const val DOB_WIDTH = 30
private const val EXPIRATION_WIDTH = 30
private const val TEMPLATE_WIDTH = 64
private const val CONTACT_NAME_WIDTH = 255
private const val DEFAULT_LEGACY_COUNT = 1

/** A kiosk photo already stored in S3, ready to be recorded against its waiver. */
data class MirroredPhoto(
    val photoId: String,
    val type: String?,
    val tag: String?,
    val fileType: String,
    val takenAt: String?,
    val s3Key: String,
    val sizeBytes: Int,
)

/** A stored waiver: its row id (the Enrich cursor) and its SmartWaiver id. */
data class WaiverRef(val rowId: Long, val waiverId: String)

/** A webhook row waiting to be processed. */
data class QueueEntry(val id: Int, val uniqueId: String, val event: String)

private val HOOK_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

data class PhotoRow(val photoId: String, val s3Key: String, val fileType: String, val tag: String?)

/** One backfill stage's saved progress; see `smartwaiver_backfill_state`. */
data class BackfillState(
    val name: String,
    val status: String,
    val cursorValue: String?,
    val doneCount: Int,
    val totalCount: Int?,
    val requestsToday: Int,
    val budgetDay: LocalDate?,
    val lastError: String?,
    val startedAt: LocalDateTime?,
    val updatedAt: LocalDateTime?,
    val finishedAt: LocalDateTime?,
)

private fun nowUtc(): LocalDateTime = LocalDateTime.now(ZoneOffset.UTC).withNano(0)

/**
 * Writes the full SmartWaiver mirror. Everything is select-then-update-or-insert because the service
 * user has no DELETE and Exposed 0.41 has no upsert; callers are single-flight, and the unique keys
 * from the migration are the backstop.
 */
@Singleton
class SmartWaiverMirrorRepository @Inject constructor() {

  /** Writes the waiver, its participants, guardian and photo rows in one transaction. */
  suspend fun saveMirror(
      waiver: SmartwaiverFullWaiver,
      photos: List<MirroredPhoto>,
      photosStatus: String,
      now: LocalDateTime = nowUtc(),
  ) =
      newSuspendedTransaction(Dispatchers.IO, db = DBMasterConnection.db) {
        upsertWaiver(waiver, photosStatus, now)
        waiver.participants.forEachIndexed { position, p -> upsertParticipant(waiver.waiverId, position, p) }
        waiver.guardian?.let { upsertGuardian(waiver.waiverId, it) }
        photos.forEach { upsertPhoto(waiver.waiverId, it, now) }
      }

  private fun upsertWaiver(waiver: SmartwaiverFullWaiver, photosStatus: String, now: LocalDateTime) {
    val referral = referralFrom(waiver)
    val primary = waiver.participants.firstOrNull()
    SmartWaiverMirrorTable.upsertWhere(
        { SmartWaiverMirrorTable.waiverId eq waiver.waiverId },
        insertOnly = {
          it[waiverStatus] = waiverStatusFor(waiver.kiosk) ?: STATUS_KIOSK
          it[checkinCount] = DEFAULT_LEGACY_COUNT
          it[requestedWaiverEmail] = DEFAULT_LEGACY_COUNT
          it[waiverId] = waiver.waiverId
        },
    ) {
      it[firstName] = waiver.firstName.orEmpty().take(NAME_WIDTH)
      it[lastName] = waiver.lastName.orEmpty().take(NAME_WIDTH)
      it[email] = waiver.email.fit(EMAIL_WIDTH)
      it[birthDate] = waiver.dob.orEmpty().take(DOB_WIDTH)
      it[dateCompleted] = waiver.createdOn.orEmpty().take(DOB_WIDTH)
      it[documentTitle] = waiver.title.fit(TITLE_WIDTH)
      // Don't overwrite a legacy answer with null when the API no longer has one.
      referral.type?.let { v -> it[referralType] = v }
      referral.source?.let { v -> it[referralSource] = v }
      waiver.guardian?.let { g ->
        it[guardianFirstName] = g.firstName.fit(NAME_WIDTH)
        it[guardianLastName] = g.lastName.fit(NAME_WIDTH)
      }
      waiverStatusFor(waiver.kiosk)?.let { v -> it[waiverStatus] = v }
      it[templateId] = waiver.templateId.fit(TEMPLATE_WIDTH)
      it[createdOnUtc] = parseApiDateTime(waiver.createdOn)
      it[expirationDate] = waiver.expirationDate.fit(EXPIRATION_WIDTH)
      it[verified] = waiver.verified
      it[kiosk] = waiver.kiosk
      it[isMinor] = waiver.isMinor
      it[tags] = tagsJson(waiver.tags)
      it[phone] = primary?.phone.fit(PHONE_WIDTH)
      it[addressLineOne] = waiver.addressLineOne.fit(ADDRESS_WIDTH)
      it[addressLineTwo] = waiver.addressLineTwo.fit(ADDRESS_WIDTH)
      it[addressCity] = waiver.addressCity.fit(NAME_WIDTH)
      it[addressState] = waiver.addressState.fit(SHORT_WIDTH)
      it[addressZip] = waiver.addressZip.fit(ZIP_WIDTH)
      it[addressCountry] = waiver.addressCountry.fit(SHORT_WIDTH)
      it[emergencyContactName] = waiver.emergencyContactName.fit(CONTACT_NAME_WIDTH)
      it[emergencyContactRelation] = waiver.emergencyContactRelation.fit(NAME_WIDTH)
      it[emergencyContactPhone] = waiver.emergencyContactPhone.fit(PHONE_WIDTH)
      it[mirroredAt] = now
      it[mirrorVersion] = MIRROR_VERSION
      it[SmartWaiverMirrorTable.photosStatus] = photosStatus
    }
  }

  private fun upsertParticipant(waiverId: String, position: Int, p: SmartwaiverParticipant) {
    SmartWaiverParticipantsTable.upsertWhere(
        {
          (SmartWaiverParticipantsTable.waiverId eq waiverId) and
              (SmartWaiverParticipantsTable.position eq position)
        },
        insertOnly = {
          it[this.waiverId] = waiverId
          it[this.position] = position
        },
    ) {
      it[firstName] = p.firstName.orEmpty().take(NAME_WIDTH)
      it[middleName] = p.middleName.fit(NAME_WIDTH)
      it[lastName] = p.lastName.orEmpty().take(NAME_WIDTH)
      it[dob] = p.dob.fit(DOB_WIDTH)
      it[isMinor] = p.isMinor
      it[minorExpired] = p.minorExpired
    }
  }

  private fun upsertGuardian(waiverId: String, g: SmartwaiverGuardian) {
    SmartWaiverGuardiansTable.upsertWhere(
        { SmartWaiverGuardiansTable.waiverId eq waiverId },
        insertOnly = { it[this.waiverId] = waiverId },
    ) {
      it[firstName] = g.firstName.fit(NAME_WIDTH)
      it[middleName] = g.middleName.fit(NAME_WIDTH)
      it[lastName] = g.lastName.fit(NAME_WIDTH)
      it[relationship] = g.relationship.fit(NAME_WIDTH)
      it[phone] = g.phone.fit(PHONE_WIDTH)
    }
  }

  private fun upsertPhoto(waiverId: String, photo: MirroredPhoto, now: LocalDateTime) {
    SmartWaiverPhotosTable.upsertWhere(
        {
          (SmartWaiverPhotosTable.waiverId eq waiverId) and
              (SmartWaiverPhotosTable.photoId eq photo.photoId)
        },
        insertOnly = {
          it[this.waiverId] = waiverId
          it[photoId] = photo.photoId.take(NAME_WIDTH)
        },
    ) {
      it[photoType] = photo.type.fit(SHORT_WIDTH)
      it[tag] = photo.tag.fit(NAME_WIDTH)
      it[fileType] = photo.fileType.take(PHOTO_FILE_TYPE_WIDTH)
      it[takenAt] = photo.takenAt.fit(DOB_WIDTH)
      it[s3Key] = photo.s3Key
      it[sizeBytes] = photo.sizeBytes
      it[mirroredAt] = now
    }
  }

  /** Photos already recorded for a waiver (what is in S3 and the DB). */
  suspend fun photosFor(waiverId: String): List<PhotoRow> =
      newSuspendedTransaction(Dispatchers.IO, db = DBMasterConnection.db) {
        SmartWaiverPhotosTable.select { SmartWaiverPhotosTable.waiverId eq waiverId }
            .orderBy(SmartWaiverPhotosTable.takenAt to SortOrder.ASC, SmartWaiverPhotosTable.id to SortOrder.ASC)
            .map {
              PhotoRow(
                  it[SmartWaiverPhotosTable.photoId],
                  it[SmartWaiverPhotosTable.s3Key],
                  it[SmartWaiverPhotosTable.fileType],
                  it[SmartWaiverPhotosTable.tag])
            }
      }

  /**
   * Adds a row for each summary whose waiverId is not stored yet (mirrorVersion stays null so the
   * Enrich stage picks it up). Returns how many were added.
   */
  suspend fun insertMissingSummaries(summaries: List<SmartwaiverSummary>): Int {
    if (summaries.isEmpty()) return 0
    return newSuspendedTransaction(Dispatchers.IO, db = DBMasterConnection.db) {
      val existing =
          summaries.map { it.waiverId }.chunked(IN_CLAUSE_CHUNK).flatMap { ids ->
            SmartWaiverMirrorTable.slice(SmartWaiverMirrorTable.waiverId)
                .select { SmartWaiverMirrorTable.waiverId inList ids }
                .map { it[SmartWaiverMirrorTable.waiverId] }
          }.toSet()
      val missing = summaries.filter { it.waiverId !in existing }.distinctBy { it.waiverId }
      missing.forEach { s ->
        SmartWaiverMirrorTable.insert {
          it[firstName] = s.firstName.orEmpty().take(NAME_WIDTH)
          it[lastName] = s.lastName.orEmpty().take(NAME_WIDTH)
          it[email] = s.email.fit(EMAIL_WIDTH)
          it[birthDate] = s.dob.orEmpty().take(DOB_WIDTH)
          it[dateCompleted] = s.createdOn.take(DOB_WIDTH)
          it[waiverId] = s.waiverId
          it[waiverStatus] = waiverStatusFor(s.kiosk) ?: STATUS_KIOSK
          it[documentTitle] = s.title.fit(TITLE_WIDTH)
          it[checkinCount] = DEFAULT_LEGACY_COUNT
          it[requestedWaiverEmail] = DEFAULT_LEGACY_COUNT
          it[templateId] = s.templateId.fit(TEMPLATE_WIDTH)
          it[createdOnUtc] = parseApiDateTime(s.createdOn)
        }
      }
      missing.size
    }
  }

  /**
   * Waivers whose full mirror is missing or was made by an older [MIRROR_VERSION], in row-id order
   * starting after [afterId]. Walking by id (rather than always taking the first rows) means a
   * waiver that keeps failing can't starve the ones behind it.
   */
  suspend fun waiversNeedingMirror(afterId: Long, limit: Int): List<WaiverRef> =
      newSuspendedTransaction(Dispatchers.IO, db = DBMasterConnection.db) {
        SmartWaiverMirrorTable.slice(SmartWaiverMirrorTable.id, SmartWaiverMirrorTable.waiverId)
            .select { (SmartWaiverMirrorTable.id greater afterId) and needsMirror()() }
            .orderBy(SmartWaiverMirrorTable.id to SortOrder.ASC)
            .limit(limit)
            .map { WaiverRef(it[SmartWaiverMirrorTable.id], it[SmartWaiverMirrorTable.waiverId]) }
      }

  suspend fun countNeedingMirror(): Int =
      newSuspendedTransaction(Dispatchers.IO, db = DBMasterConnection.db) {
        SmartWaiverMirrorTable.select(needsMirror()).count().toInt()
      }

  private fun needsMirror(): SqlExpressionBuilder.() -> Op<Boolean> = {
    SmartWaiverMirrorTable.mirrorVersion.isNull() or
        (SmartWaiverMirrorTable.mirrorVersion less MIRROR_VERSION)
  }

  /** Waivers that were fully mirrored but have no participant row, which should not happen. */
  suspend fun countMirroredWithoutParticipants(): Int =
      newSuspendedTransaction(Dispatchers.IO, db = DBMasterConnection.db) {
        var count = 0
        exec(
            "SELECT COUNT(*) FROM `dms-makermanager`.smartwaiver_data d " +
                "WHERE d.mirrorVersion IS NOT NULL AND NOT EXISTS " +
                "(SELECT 1 FROM `dms-makermanager`.smartwaiver_participants p WHERE p.waiverId = d.waiverId)") { rs ->
              if (rs.next()) count = rs.getInt(1)
            }
        count
      }

  suspend fun countCheckins(): Int =
      newSuspendedTransaction(Dispatchers.IO, db = DBMasterConnection.db) {
        SmartWaiverCheckinsTable.selectAll().count().toInt()
      }

  suspend fun countWaivers(): Int =
      newSuspendedTransaction(Dispatchers.IO, db = DBMasterConnection.db) {
        SmartWaiverMirrorTable.selectAll().count().toInt()
      }

  /** Stores check-ins not seen before (they never change). Returns how many were new. */
  suspend fun insertNewCheckins(checkins: List<SmartwaiverCheckin>, now: LocalDateTime = nowUtc()): Int {
    if (checkins.isEmpty()) return 0
    return newSuspendedTransaction(Dispatchers.IO, db = DBMasterConnection.db) {
      val ids = checkins.map { it.checkinId }
      val existing =
          SmartWaiverCheckinsTable.slice(SmartWaiverCheckinsTable.checkinId)
              .select { SmartWaiverCheckinsTable.checkinId inList ids }
              .map { it[SmartWaiverCheckinsTable.checkinId] }
              .toSet()
      val fresh = checkins.filter { it.checkinId !in existing }.distinctBy { it.checkinId }
      SmartWaiverCheckinsTable.batchInsert(fresh) { c ->
        this[SmartWaiverCheckinsTable.checkinId] = c.checkinId
        this[SmartWaiverCheckinsTable.waiverId] = c.waiverId.take(NAME_WIDTH)
        this[SmartWaiverCheckinsTable.position] = c.position
        this[SmartWaiverCheckinsTable.templateId] = c.templateId.fit(TEMPLATE_WIDTH)
        this[SmartWaiverCheckinsTable.isMinor] = c.isMinor?.let { it != 0 }
        this[SmartWaiverCheckinsTable.checkedInAt] = parseApiDateTime(c.date) ?: now
        this[SmartWaiverCheckinsTable.dateSigned] = parseApiDateTime(c.dateSigned)
        this[SmartWaiverCheckinsTable.mirroredAt] = now
      }
      fresh.size
    }
  }

  /** Unprocessed webhook rows from the last [lookbackDays] that have failed fewer than [maxAttempts] times. */
  suspend fun retryableQueueEntries(maxAttempts: Int, lookbackDays: Long): List<QueueEntry> {
    val cutoff = LocalDateTime.now().minusDays(lookbackDays).format(HOOK_TIME)
    return newSuspendedTransaction(Dispatchers.IO, db = DBMasterConnection.db) {
      SmartWaiverHookAttemptsTable.select {
            SmartWaiverHookAttemptsTable.processedAt.isNull() and
                (SmartWaiverHookAttemptsTable.createdAt greaterEq cutoff) and
                (SmartWaiverHookAttemptsTable.attempts less maxAttempts)
          }
          .orderBy(SmartWaiverHookAttemptsTable.id to SortOrder.ASC)
          .map {
            QueueEntry(
                it[SmartWaiverHookAttemptsTable.id],
                it[SmartWaiverHookAttemptsTable.uniqueId],
                it[SmartWaiverHookAttemptsTable.hook])
          }
    }
  }

  suspend fun markQueueProcessed(ids: List<Int>) {
    if (ids.isEmpty()) return
    val now = LocalDateTime.now().format(HOOK_TIME)
    newSuspendedTransaction(Dispatchers.IO, db = DBMasterConnection.db) {
      SmartWaiverHookAttemptsTable.update({ SmartWaiverHookAttemptsTable.id inList ids }) {
        it[processedAt] = now
        it[updatedAt] = now
      }
    }
  }

  suspend fun recordQueueFailure(id: Int, error: String?) {
    val now = LocalDateTime.now().format(HOOK_TIME)
    newSuspendedTransaction(Dispatchers.IO, db = DBMasterConnection.db) {
      SmartWaiverHookAttemptsTable.update({ SmartWaiverHookAttemptsTable.id eq id }) {
        with(SqlExpressionBuilder) { it.update(attempts, attempts + 1) }
        it[lastError] = error.fit(LAST_ERROR_WIDTH)
        it[updatedAt] = now
      }
    }
  }

  suspend fun latestCheckinAt(): LocalDateTime? =
      newSuspendedTransaction(Dispatchers.IO, db = DBMasterConnection.db) {
        SmartWaiverCheckinsTable.slice(SmartWaiverCheckinsTable.checkedInAt.max())
            .selectAll()
            .firstOrNull()
            ?.get(SmartWaiverCheckinsTable.checkedInAt.max())
      }

  suspend fun getState(name: String): BackfillState? =
      newSuspendedTransaction(Dispatchers.IO, db = DBMasterConnection.db) {
        SmartWaiverBackfillStateTable.select { SmartWaiverBackfillStateTable.name eq name }
            .firstOrNull()
            ?.let {
              BackfillState(
                  name = it[SmartWaiverBackfillStateTable.name],
                  status = it[SmartWaiverBackfillStateTable.status],
                  cursorValue = it[SmartWaiverBackfillStateTable.cursorValue],
                  doneCount = it[SmartWaiverBackfillStateTable.doneCount],
                  totalCount = it[SmartWaiverBackfillStateTable.totalCount],
                  requestsToday = it[SmartWaiverBackfillStateTable.requestsToday],
                  budgetDay = it[SmartWaiverBackfillStateTable.budgetDay],
                  lastError = it[SmartWaiverBackfillStateTable.lastError],
                  startedAt = it[SmartWaiverBackfillStateTable.startedAt],
                  updatedAt = it[SmartWaiverBackfillStateTable.updatedAt],
                  finishedAt = it[SmartWaiverBackfillStateTable.finishedAt],
              )
            }
      }

  suspend fun saveState(state: BackfillState) =
      newSuspendedTransaction(Dispatchers.IO, db = DBMasterConnection.db) {
        SmartWaiverBackfillStateTable.upsertWhere(
            { SmartWaiverBackfillStateTable.name eq state.name },
            insertOnly = { it[name] = state.name },
        ) {
          it[status] = state.status
          it[cursorValue] = state.cursorValue
          it[doneCount] = state.doneCount
          it[totalCount] = state.totalCount
          it[requestsToday] = state.requestsToday
          it[budgetDay] = state.budgetDay
          it[lastError] = state.lastError.fit(LAST_ERROR_WIDTH)
          it[startedAt] = state.startedAt
          it[updatedAt] = state.updatedAt
          it[finishedAt] = state.finishedAt
        }
      }

  /** Mirrored waivers per `yyyy-MM` of their creation date, for reconciliation. */
  suspend fun countsByMonth(): Map<String, Int> =
      newSuspendedTransaction(Dispatchers.IO, db = DBMasterConnection.db) {
        val counts = mutableMapOf<String, Int>()
        exec(
            "SELECT LEFT(dateCompleted, $MONTH_LENGTH), COUNT(*) " +
                "FROM `dms-makermanager`.smartwaiver_data " +
                "GROUP BY LEFT(dateCompleted, $MONTH_LENGTH)") { rs ->
              while (rs.next()) counts[rs.getString(1)] = rs.getInt(2)
            }
        counts
      }

  /** Waivers whose photo mirroring never finished (photosStatus is not NONE/COMPLETE). */
  suspend fun countIncompletePhotos(): Int =
      newSuspendedTransaction(Dispatchers.IO, db = DBMasterConnection.db) {
        SmartWaiverMirrorTable.select {
              SmartWaiverMirrorTable.mirrorVersion.isNotNull() and
                  (SmartWaiverMirrorTable.photosStatus eq PHOTOS_PARTIAL)
            }
            .count()
            .toInt()
      }

  companion object {
    const val PHOTOS_NONE = "NONE"
    const val PHOTOS_COMPLETE = "COMPLETE"
    const val PHOTOS_PARTIAL = "PARTIAL"
  }
}

private const val IN_CLAUSE_CHUNK = 500
private const val LAST_ERROR_WIDTH = 500
private const val MONTH_LENGTH = 7
private const val PHOTO_FILE_TYPE_WIDTH = 10

/** Update the row matching [where], or insert one if none does; [insertOnly] adds insert-only values. */
private fun <T : Table> T.upsertWhere(
    where: SqlExpressionBuilder.() -> Op<Boolean>,
    insertOnly: T.(UpdateBuilder<*>) -> Unit = {},
    common: T.(UpdateBuilder<*>) -> Unit,
) {
  if (select(where).limit(1).any()) {
    update(where) { common(this@upsertWhere, it) }
  } else {
    insert {
      common(this@upsertWhere, it)
      insertOnly(this@upsertWhere, it)
    }
  }
}

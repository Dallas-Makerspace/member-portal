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
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.max
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.UpdateBuilder
import org.jetbrains.exposed.exceptions.ExposedSQLException
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

/** The few fields the reports and the profile photo view read back from the mirror. */
data class LocalWaiver(
    val waiverId: String,
    val firstName: String,
    val lastName: String,
    val email: String?,
    val createdOn: String,
    val templateId: String?,
    val title: String?,
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
          it[waiverId] = waiver.waiverId
          it[firstName] = ""
          it[lastName] = ""
          it[birthDate] = ""
          it[dateCompleted] = ""
          it[waiverStatus] = STATUS_KIOSK
          it[checkinCount] = DEFAULT_LEGACY_COUNT
          it[requestedWaiverEmail] = DEFAULT_LEGACY_COUNT
        },
    ) {
      // Only write what the API gave us, so a missing field never blanks a value already stored.
      waiver.firstName?.let { v -> it[firstName] = v.take(NAME_WIDTH) }
      waiver.lastName?.let { v -> it[lastName] = v.take(NAME_WIDTH) }
      waiver.email?.let { v -> it[email] = v.take(EMAIL_WIDTH) }
      waiver.dob?.let { v -> it[birthDate] = v.take(DOB_WIDTH) }
      waiver.createdOn?.let { v ->
        it[dateCompleted] = v.take(DOB_WIDTH)
        parseApiDateTime(v)?.let { d -> it[createdOnUtc] = d }
      }
      waiver.title?.let { v -> it[documentTitle] = v.take(TITLE_WIDTH) }
      // Don't overwrite a legacy answer with null when the API no longer has one.
      referral.type?.let { v -> it[referralType] = v }
      referral.source?.let { v -> it[referralSource] = v }
      waiver.guardian?.let { g ->
        g.firstName?.let { v -> it[guardianFirstName] = v.take(NAME_WIDTH) }
        g.lastName?.let { v -> it[guardianLastName] = v.take(NAME_WIDTH) }
      }
      waiverStatusFor(waiver.kiosk)?.let { v -> it[waiverStatus] = v }
      waiver.templateId?.let { v -> it[templateId] = v.take(TEMPLATE_WIDTH) }
      waiver.expirationDate?.let { v -> it[expirationDate] = v.take(EXPIRATION_WIDTH) }
      waiver.verified?.let { v -> it[verified] = v }
      waiver.kiosk?.let { v -> it[kiosk] = v }
      waiver.isMinor?.let { v -> it[isMinor] = v }
      it[tags] = tagsJson(waiver.tags)
      primary?.phone?.let { v -> it[phone] = v.take(PHONE_WIDTH) }
      waiver.addressLineOne?.let { v -> it[addressLineOne] = v.take(ADDRESS_WIDTH) }
      waiver.addressLineTwo?.let { v -> it[addressLineTwo] = v.take(ADDRESS_WIDTH) }
      waiver.addressCity?.let { v -> it[addressCity] = v.take(NAME_WIDTH) }
      waiver.addressState?.let { v -> it[addressState] = v.take(SHORT_WIDTH) }
      waiver.addressZip?.let { v -> it[addressZip] = v.take(ZIP_WIDTH) }
      waiver.addressCountry?.let { v -> it[addressCountry] = v.take(SHORT_WIDTH) }
      waiver.emergencyContactName?.let { v -> it[emergencyContactName] = v.take(CONTACT_NAME_WIDTH) }
      waiver.emergencyContactRelation?.let { v -> it[emergencyContactRelation] = v.take(NAME_WIDTH) }
      waiver.emergencyContactPhone?.let { v -> it[emergencyContactPhone] = v.take(PHONE_WIDTH) }
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
      it[photoType] = photo.type.fit(PHOTO_TYPE_WIDTH)
      it[tag] = photo.tag.fit(NAME_WIDTH)
      it[fileType] = photo.fileType.take(PHOTO_FILE_TYPE_WIDTH)
      it[takenAt] = photo.takenAt.fit(DOB_WIDTH)
      it[s3Key] = photo.s3Key
      it[sizeBytes] = photo.sizeBytes
      it[mirroredAt] = now
    }
  }

  /**
   * Waivers signed from the start of [from] through the end of [to] (UTC, like the API's search),
   * newest first. Filters on the `dateCompleted` text, which every row has, rather than on
   * `createdOnUtc`, which rows written by the original minimal path lack.
   */
  suspend fun waiversBetween(from: LocalDate, to: LocalDate): List<LocalWaiver> =
      newSuspendedTransaction(Dispatchers.IO, db = DBMasterConnection.db) {
        SmartWaiverMirrorTable.select {
              (SmartWaiverMirrorTable.dateCompleted greaterEq "$from 00:00:00") and
                  (SmartWaiverMirrorTable.dateCompleted lessEq "$to 23:59:59")
            }
            .orderBy(SmartWaiverMirrorTable.dateCompleted to SortOrder.DESC)
            .map { it.toLocalWaiver() }
      }

  /** Newest waivers where the signer or any participant has this name (case-insensitive). */
  suspend fun waiversByName(firstName: String, lastName: String, limit: Int): List<LocalWaiver> =
      newSuspendedTransaction(Dispatchers.IO, db = DBMasterConnection.db) {
        val first = firstName.lowercase()
        val last = lastName.lowercase()
        val viaParticipants =
            SmartWaiverParticipantsTable.slice(SmartWaiverParticipantsTable.waiverId)
                .select {
                  (SmartWaiverParticipantsTable.firstName.lowerCase() eq first) and
                      (SmartWaiverParticipantsTable.lastName.lowerCase() eq last)
                }
                .map { it[SmartWaiverParticipantsTable.waiverId] }
        SmartWaiverMirrorTable.select {
              ((SmartWaiverMirrorTable.firstName.lowerCase() eq first) and
                  (SmartWaiverMirrorTable.lastName.lowerCase() eq last)) or
                  (SmartWaiverMirrorTable.waiverId inList viaParticipants)
            }
            .orderBy(SmartWaiverMirrorTable.dateCompleted to SortOrder.DESC)
            .limit(limit)
            .map { it.toLocalWaiver() }
      }

  private fun ResultRow.toLocalWaiver() =
      LocalWaiver(
          waiverId = this[SmartWaiverMirrorTable.waiverId],
          firstName = this[SmartWaiverMirrorTable.firstName],
          lastName = this[SmartWaiverMirrorTable.lastName],
          email = this[SmartWaiverMirrorTable.email],
          createdOn = this[SmartWaiverMirrorTable.dateCompleted],
          templateId = this[SmartWaiverMirrorTable.templateId],
          title = this[SmartWaiverMirrorTable.documentTitle],
      )

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
      var added = 0
      missing.forEach { s ->
        try {
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
          added++
        } catch (e: ExposedSQLException) {
          if (!e.isDuplicateKey()) throw e // stored meanwhile by another copy
        }
      }
      added
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
        ((SmartWaiverMirrorTable.mirrorVersion greater GONE_VERSION) and
            (SmartWaiverMirrorTable.mirrorVersion less MIRROR_VERSION))
  }

  /** Records that SmartWaiver no longer has this waiver, so it stops counting as work still to do. */
  suspend fun markGone(waiverId: String, now: LocalDateTime = nowUtc()) {
    newSuspendedTransaction(Dispatchers.IO, db = DBMasterConnection.db) {
      SmartWaiverMirrorTable.update({ SmartWaiverMirrorTable.waiverId eq waiverId }) {
        it[mirrorVersion] = GONE_VERSION
        it[photosStatus] = PHOTOS_GONE
        it[mirroredAt] = now
      }
    }
  }

  suspend fun countGone(): Int =
      newSuspendedTransaction(Dispatchers.IO, db = DBMasterConnection.db) {
        SmartWaiverMirrorTable.select { SmartWaiverMirrorTable.mirrorVersion eq GONE_VERSION }.count().toInt()
      }

  /** Waivers that were fully mirrored but have no participant row, which should not happen. */
  suspend fun countMirroredWithoutParticipants(): Int =
      newSuspendedTransaction(Dispatchers.IO, db = DBMasterConnection.db) {
        var count = 0
        exec(
            "SELECT COUNT(*) FROM `dms-makermanager`.smartwaiver_data d " +
                "WHERE d.mirrorVersion > 0 AND NOT EXISTS " +
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
  suspend fun insertNewCheckins(checkins: List<SmartwaiverCheckin>, now: LocalDateTime = nowUtc()): Int =
      try {
        insertNewCheckinsOnce(checkins, now)
      } catch (e: ExposedSQLException) {
        // Another writer (a catch-up sync, or a second container) stored some of them meanwhile.
        if (e.isDuplicateKey()) insertNewCheckinsOnce(checkins, now) else throw e
      }

  private suspend fun insertNewCheckinsOnce(checkins: List<SmartwaiverCheckin>, now: LocalDateTime): Int {
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

  /**
   * Makes [owner] the one copy that runs the backfill (the run row's cursorValue holds the owner).
   * A copy that was running before notices at its next checkpoint and stops.
   */
  suspend fun claimRun(owner: String, state: BackfillState) = saveState(state.copy(cursorValue = owner))

  /**
   * One transaction per unit of work: renew the run row if [owner] still holds it, then save the
   * stage's progress. Returns false, writing nothing, when another copy has taken over or the run
   * was stopped.
   */
  suspend fun saveUnit(stage: BackfillState, owner: String, run: BackfillState): Boolean =
      newSuspendedTransaction(Dispatchers.IO, db = DBMasterConnection.db) {
        val held = renewRunRow(owner, run)
        if (held) saveStageRow(stage)
        held
      }

  /** Renews the run row without saving a stage (while paused or waiting). False if not the owner. */
  suspend fun renewRun(owner: String, run: BackfillState): Boolean =
      newSuspendedTransaction(Dispatchers.IO, db = DBMasterConnection.db) { renewRunRow(owner, run) }

  /** Marks the run IDLE and releases it, keeping its counters, so any copy still running stops. */
  suspend fun markRunIdle(now: LocalDateTime = nowUtc()) {
    newSuspendedTransaction(Dispatchers.IO, db = DBMasterConnection.db) {
      SmartWaiverBackfillStateTable.update({ SmartWaiverBackfillStateTable.name eq RUN_STATE_NAME }) {
        it[status] = "IDLE"
        it[cursorValue] = null
        it[updatedAt] = now
      }
    }
  }

  private fun renewRunRow(owner: String, run: BackfillState): Boolean {
    val updated =
        SmartWaiverBackfillStateTable.update({
          (SmartWaiverBackfillStateTable.name eq RUN_STATE_NAME) and
              (SmartWaiverBackfillStateTable.cursorValue eq owner)
        }) {
          it[status] = run.status
          it[doneCount] = run.doneCount
          it[requestsToday] = run.requestsToday
          it[budgetDay] = run.budgetDay
          it[lastError] = run.lastError.fit(LAST_ERROR_WIDTH)
          it[startedAt] = run.startedAt
          it[updatedAt] = run.updatedAt
          it[finishedAt] = run.finishedAt
        }
    if (updated > 0) return true
    // A driver that counts changed rows reports 0 for an identical update, so confirm by reading.
    return SmartWaiverBackfillStateTable.select {
          (SmartWaiverBackfillStateTable.name eq RUN_STATE_NAME) and
              (SmartWaiverBackfillStateTable.cursorValue eq owner)
        }
        .any()
  }

  private fun saveStageRow(state: BackfillState) {
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

  suspend fun saveState(state: BackfillState) =
      newSuspendedTransaction(Dispatchers.IO, db = DBMasterConnection.db) { saveStageRow(state) }

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
    const val PHOTOS_GONE = "GONE"

    /** `mirrorVersion` of a waiver SmartWaiver no longer has (real versions start at 1). */
    const val GONE_VERSION = 0

    /** Name of the backfill state row that describes the run as a whole. */
    const val RUN_STATE_NAME = "run"
  }
}

private const val IN_CLAUSE_CHUNK = 500
private const val LAST_ERROR_WIDTH = 500
private const val MONTH_LENGTH = 7
private const val PHOTO_FILE_TYPE_WIDTH = 10
private const val PHOTO_TYPE_WIDTH = 30

/**
 * Updates the row matching [where], or inserts one if none does ([insertOnly] values go in first, so
 * [common] can override them). Updating first is one statement for rows that exist, the usual case.
 * If the insert hits a duplicate key, another writer inserted it first (a second container during a
 * deploy) or the driver counted only changed rows and the update matched an identical row: the row
 * exists either way.
 */
private fun <T : Table> T.upsertWhere(
    where: SqlExpressionBuilder.() -> Op<Boolean>,
    insertOnly: T.(UpdateBuilder<*>) -> Unit = {},
    common: T.(UpdateBuilder<*>) -> Unit,
) {
  if (update(where) { common(this@upsertWhere, it) } > 0) return
  try {
    insert {
      insertOnly(this@upsertWhere, it)
      common(this@upsertWhere, it)
    }
  } catch (e: ExposedSQLException) {
    if (!e.isDuplicateKey()) throw e
  }
}

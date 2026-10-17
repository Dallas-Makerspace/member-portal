package org.dallasmakerspace.smartwaiver

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.datetime

// Mirror tables from smartwaiver-mirror-migration.sql (database `dms-makermanager`, DB_MASTER).

/**
 * The same physical table as [SmartWaiverTable], with the mirror's extra columns. Kept separate
 * because the legacy webhook code does full-row selects on [SmartWaiverTable]; adding columns there
 * would break it between deploying and the migration being applied. Only mirror code uses this.
 */
@Suppress("MagicNumber")
object SmartWaiverMirrorTable : Table("`dms-makermanager`.smartwaiver_data") {
  val id = long("id").autoIncrement()
  val firstName = varchar("firstName", 100)
  val lastName = varchar("lastName", 100)
  val email = varchar("email", 100).nullable()
  val birthDate = varchar("birthDate", 100)
  val dateCompleted = varchar("dateCompleted", 100)
  val waiverId = varchar("waiverId", 100)
  val waiverStatus = varchar("waiverStatus", 100)
  val documentTitle = varchar("documentTitle", 100).nullable()
  val checkinCount = integer("checkinCount")
  val referralType = varchar("referralType", 100).nullable()
  val referralSource = varchar("referralSource", 100).nullable()
  val requestedWaiverEmail = integer("requestedWaiverEmail")
  val guardianFirstName = varchar("guardianFirstName", 100).nullable()
  val guardianLastName = varchar("guardianLastName", 100).nullable()
  val templateId = varchar("templateId", 64).nullable()
  val createdOnUtc = datetime("createdOnUtc").nullable()
  val expirationDate = varchar("expirationDate", 30).nullable()
  val verified = bool("verified").nullable()
  val kiosk = bool("kiosk").nullable()
  val isMinor = bool("isMinor").nullable()
  val tags = text("tags").nullable()
  val phone = varchar("phone", 50).nullable()
  val addressLineOne = varchar("addressLineOne", 255).nullable()
  val addressLineTwo = varchar("addressLineTwo", 255).nullable()
  val addressCity = varchar("addressCity", 100).nullable()
  val addressState = varchar("addressState", 50).nullable()
  val addressZip = varchar("addressZip", 20).nullable()
  val addressCountry = varchar("addressCountry", 50).nullable()
  val emergencyContactName = varchar("emergencyContactName", 255).nullable()
  val emergencyContactRelation = varchar("emergencyContactRelation", 100).nullable()
  val emergencyContactPhone = varchar("emergencyContactPhone", 50).nullable()
  val mirroredAt = datetime("mirroredAt").nullable()
  val mirrorVersion = integer("mirrorVersion").nullable()
  val photosStatus = varchar("photosStatus", 20).nullable()
  override val primaryKey = PrimaryKey(id)
}

/**
 * `smartwaiver_hooks` with the retry columns added by the migration. Separate from
 * [SmartWaiverHooksTable] so the legacy path never references columns that exist only afterwards.
 */
@Suppress("MagicNumber")
object SmartWaiverHookAttemptsTable : Table("`dms-makermanager`.smartwaiver_hooks") {
  val id = integer("id")
  val hook = varchar("hook", 255)
  val uniqueId = varchar("unique_id", 255)
  val processedAt = varchar("processed_at", 30).nullable()
  val createdAt = varchar("created_at", 30).nullable()
  val updatedAt = varchar("updated_at", 30).nullable()
  val attempts = integer("attempts")
  val lastError = varchar("last_error", 500).nullable()
  override val primaryKey = PrimaryKey(id)
}

@Suppress("MagicNumber")
object SmartWaiverParticipantsTable : Table("`dms-makermanager`.smartwaiver_participants") {
  val id = long("id").autoIncrement()
  val waiverId = varchar("waiverId", 100)
  val position = integer("position")
  val firstName = varchar("firstName", 100)
  val middleName = varchar("middleName", 100).nullable()
  val lastName = varchar("lastName", 100)
  val dob = varchar("dob", 30).nullable()
  val isMinor = bool("isMinor").nullable()
  val minorExpired = bool("minorExpired").nullable()
  override val primaryKey = PrimaryKey(id)
}

@Suppress("MagicNumber")
object SmartWaiverGuardiansTable : Table("`dms-makermanager`.smartwaiver_guardians") {
  val waiverId = varchar("waiverId", 100)
  val firstName = varchar("firstName", 100).nullable()
  val middleName = varchar("middleName", 100).nullable()
  val lastName = varchar("lastName", 100).nullable()
  val relationship = varchar("relationship", 100).nullable()
  val phone = varchar("phone", 50).nullable()
  override val primaryKey = PrimaryKey(waiverId)
}

@Suppress("MagicNumber")
object SmartWaiverPhotosTable : Table("`dms-makermanager`.smartwaiver_photos") {
  val id = long("id").autoIncrement()
  val waiverId = varchar("waiverId", 100)
  val photoId = varchar("photoId", 100)
  val photoType = varchar("photoType", 30).nullable()
  val tag = varchar("tag", 100).nullable()
  val fileType = varchar("fileType", 10)
  val takenAt = varchar("takenAt", 30).nullable()
  val s3Key = varchar("s3Key", 255)
  val sizeBytes = integer("sizeBytes").nullable()
  val mirroredAt = datetime("mirroredAt")
  override val primaryKey = PrimaryKey(id)
}

@Suppress("MagicNumber")
object SmartWaiverCheckinsTable : Table("`dms-makermanager`.smartwaiver_checkins") {
  val checkinId = long("checkinId")
  val waiverId = varchar("waiverId", 100)
  val position = integer("position").nullable()
  val templateId = varchar("templateId", 64).nullable()
  val isMinor = bool("isMinor").nullable()
  val checkedInAt = datetime("checkedInAt")
  val dateSigned = datetime("dateSigned").nullable()
  val mirroredAt = datetime("mirroredAt")
  override val primaryKey = PrimaryKey(checkinId)
}

@Suppress("MagicNumber")
object SmartWaiverBackfillStateTable : Table("`dms-makermanager`.smartwaiver_backfill_state") {
  val name = varchar("name", 40)
  val status = varchar("status", 20)
  val cursorValue = varchar("cursorValue", 100).nullable()
  val doneCount = integer("doneCount")
  val totalCount = integer("totalCount").nullable()
  val requestsToday = integer("requestsToday")
  val budgetDay = date("budgetDay").nullable()
  val lastError = varchar("lastError", 500).nullable()
  val startedAt = datetime("startedAt").nullable()
  val updatedAt = datetime("updatedAt").nullable()
  val finishedAt = datetime("finishedAt").nullable()
  override val primaryKey = PrimaryKey(name)
}

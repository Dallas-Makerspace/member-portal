package org.dallasmakerspace.notifications.db

import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime

/**
 * Exposed table for `notifications` - the notification outbox and unified communication log. Rows
 * are keyed by [profileId], an FK to `profile.id` (enforced at DB level).
 */
@Suppress("MagicNumber")
object NotificationsTable : IdTable<Long>("notifications") {
  val idColumn: Column<EntityID<Long>> = long("id").autoIncrement().entityId()
  val profileId = integer("profile_id").index() // FK to profile.id (enforced at DB level)
  val topic = varchar("topic", 64).index()
  val channel = varchar("channel", 32)
  val aggregation = varchar("aggregation", 16)
  val templateName = varchar("template_name", 128)
  val payload = text("payload").nullable()
  val subject = varchar("subject", 512)
  val body = text("body").nullable()
  val status = varchar("status", 32).index()
  val attempts = integer("attempts").default(0)
  val nextAttemptAt = datetime("next_attempt_at").nullable()
  val dedupeKey = varchar("dedupe_key", 255).nullable().uniqueIndex()
  val providerId = varchar("provider_id", 128).nullable()
  val lastError = text("last_error").nullable()
  val sentAt = datetime("sent_at").nullable()
  val readAt = datetime("read_at").nullable()
  val createdAt = datetime("created_at")
  val updatedAt = datetime("updated_at")

  override val id: Column<EntityID<Long>>
    get() = idColumn
}

/** Exposed table for `notification_preferences` - per-member opt-in and aggregation override. */
@Suppress("MagicNumber")
object NotificationPreferencesTable : Table("notification_preferences") {
  val profileId = integer("profile_id") // FK to profile.id (enforced at DB level)
  val topic = varchar("topic", 64)
  val channel = varchar("channel", 32)
  val enabled = bool("enabled").default(true)
  val aggregation = varchar("aggregation", 16).nullable()
  val updatedAt = datetime("updated_at")

  override val primaryKey = PrimaryKey(profileId, topic, channel)
}

/** Exposed table for `notification_suppressions` - members suppressed from notifications. */
@Suppress("MagicNumber")
object NotificationSuppressionsTable : Table("notification_suppressions") {
  val id = long("id").autoIncrement()
  val profileId = integer("profile_id").index() // FK to profile.id (enforced at DB level)
  val email = varchar("email", 255).nullable()
  val reason = varchar("reason", 32)
  val createdAt = datetime("created_at")

  override val primaryKey = PrimaryKey(id)
}

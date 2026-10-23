package org.dallasmakerspace.bambuddy.notifications.db

import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime

/**
 * Exposed table for `bambu_ephemeral_notifications` — one-shot Bambu print watches. Rows are keyed
 * by [profileId] (FK to `profile.id`); terminal rows are tombstones purged after retention.
 * DDL lives in `service/docs/sql/bambu-notifications-migration.sql` and `create.sql`.
 */
@Suppress("MagicNumber")
object EphemeralNotificationsTable : IdTable<Long>("bambu_ephemeral_notifications") {
  val idColumn: Column<EntityID<Long>> = long("id").autoIncrement().entityId()
  val profileId = integer("profile_id").index()
  val scope = varchar("scope", 32)
  val printerId = integer("printer_id")
  val printerName = varchar("printer_name", 128)
  val queueId = integer("queue_id").nullable()
  val filename = varchar("filename", 512).nullable()
  val jobName = varchar("job_name", 512).nullable()
  val triggerEvents = varchar("trigger_events", 128)
  val status = varchar("status", 16).index()
  val rearmCount = integer("rearm_count").default(0)
  val createdAt = datetime("created_at")
  val expiresAt = datetime("expires_at")
  val terminalAt = datetime("terminal_at").nullable()
  val firedNotificationId = long("fired_notification_id").nullable()
  val terminalReason = varchar("terminal_reason", 255).nullable()

  override val id: Column<EntityID<Long>>
    get() = idColumn
}

/** Exposed table for `bambu_pending_offline_alerts` — held printer-offline committee alerts. */
@Suppress("MagicNumber")
object PendingOfflineAlertsTable : IdTable<Long>("bambu_pending_offline_alerts") {
  val idColumn: Column<EntityID<Long>> = long("id").autoIncrement().entityId()
  val printerId = integer("printer_id")
  val printerName = varchar("printer_name", 128)
  val offlineSince = datetime("offline_since")
  val releaseAt = datetime("release_at")
  val status = varchar("status", 16).index()
  val resolvedAt = datetime("resolved_at").nullable()
  val notificationsCreated = integer("notifications_created").nullable()

  override val id: Column<EntityID<Long>>
    get() = idColumn
}

/**
 * Exposed table for `bambu_committee_subscriptions` — members subscribed to Bambu committee
 * (printer offline) alerts. Same shape as `committee_subscriptions` minus the committee id.
 */
object BambuCommitteeSubscriptionsTable : Table("bambu_committee_subscriptions") {
  val profileId = integer("profile_id")
  val createdAt = datetime("created_at")

  override val primaryKey = PrimaryKey(profileId)
}

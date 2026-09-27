package org.dallasmakerspace.members.db

import java.time.LocalDateTime
import kotlinx.coroutines.Dispatchers
import org.dallasmakerspace.core.DBMemberPortalConnection
import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.alias
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

/** Exposed table for the mariaDB table - profile. */
@Suppress("MagicNumber")
object ProfileTable : IdTable<String>("profile") {
  val idColumn: Column<EntityID<Int>> = integer("id").autoIncrement().entityId()
  val username: Column<EntityID<String>> = varchar("username", 100).entityId()
  val isEnabled: Column<Boolean> = bool("is_enabled").default(false)
  val discourseUsername: Column<String?> = varchar("discourse_username", 100).nullable()
  val discourseAvatarUrl: Column<String?> = varchar("discourse_avatar_url", 2083).nullable()
  val discordUserId: Column<String?> = varchar("discord_userid", 100).nullable()
  val discordUsername: Column<String?> = varchar("discord_username", 100).nullable()
  val discordAvatarUrl: Column<String?> = varchar("discord_avatar_url", 2083).nullable()
  val discordWebhookId: Column<String?> = varchar("discord_webhook_id", 100).nullable()
  val linkedinUsername: Column<String?> = varchar("linkedin_username", 100).nullable()
  // Uploaded profile photo (see the "storage" package). Distinct from avatarUrl/discourseAvatarUrl
  // above, which are refreshed from external sources and would otherwise clobber an upload.
  val profilePhotoKey: Column<String?> = varchar("profile_photo_key", 512).nullable()
  val profilePhotoUpdatedAt: Column<LocalDateTime?> =
      datetime("profile_photo_updated_at").nullable()
  val attributes: Column<String?> = text("attributes").nullable()
  override val id: Column<EntityID<String>>
    get() = username
}

/** Column aliases used in other tables to reference the profile table. */
object ProfileColumnAliases {
  val actorProfileAlias = ProfileTable.alias("actorProfile")
  val subjectProfileAlias = ProfileTable.alias("subjectProfile")
}

class ProfileDAO(username: EntityID<String>) : Entity<String>(username) {
  companion object : EntityClass<String, ProfileDAO>(ProfileTable)

  var idColumn by ProfileTable.idColumn
  var isEnabled by ProfileTable.isEnabled
  var discourseUsername by ProfileTable.discourseUsername
  var discourseAvatarUrl by ProfileTable.discourseAvatarUrl
  var discordUserId by ProfileTable.discordUserId
  var discordUsername by ProfileTable.discordUsername
  var discordAvatarUrl by ProfileTable.discordAvatarUrl
  var discordWebhookId by ProfileTable.discordWebhookId
  var linkedinUsername by ProfileTable.linkedinUsername
  var profilePhotoKey by ProfileTable.profilePhotoKey
  var profilePhotoUpdatedAt by ProfileTable.profilePhotoUpdatedAt
  var attributes by ProfileTable.attributes
}

suspend fun <T> suspendTransaction(block: Transaction.() -> T): T =
    newSuspendedTransaction(Dispatchers.IO, statement = block, db = DBMemberPortalConnection.db)

package org.dallasmakerspace.members.db

import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.javatime.datetime

/**
 * Exposed table for the mariaDB table - member_settings. Append-only: every change is written as a
 * new record so an audit trail of setting changes is preserved. The latest record per (profile_id,
 * setting_key) holds the current value; a NULL setting_value records a clear.
 */
@Suppress("MagicNumber")
object MemberSettingsTable : IdTable<Int>("member_settings") {
  val idColumn = integer("id").autoIncrement().entityId()
  val profileId = integer("profile_id").index() // FK to profile.id (enforced at DB level)
  val settingKey = varchar("setting_key", 100).index()
  val settingValue = text("setting_value").nullable()
  val updatedBy =
      integer("updated_by").nullable().index() // FK to profile.id (enforced at DB level)
  val updatedAt = datetime("updated_at")

  override val id: Column<EntityID<Int>>
    get() = idColumn
}

class MemberSettingsDAO(id: EntityID<Int>) : Entity<Int>(id) {
  companion object : EntityClass<Int, MemberSettingsDAO>(MemberSettingsTable)

  var profileId by MemberSettingsTable.profileId
  var settingKey by MemberSettingsTable.settingKey
  var settingValue by MemberSettingsTable.settingValue
  var updatedBy by MemberSettingsTable.updatedBy
  var updatedAt by MemberSettingsTable.updatedAt
}

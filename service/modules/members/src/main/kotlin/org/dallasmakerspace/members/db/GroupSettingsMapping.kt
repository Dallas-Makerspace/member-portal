package org.dallasmakerspace.members.db

import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.javatime.datetime

/**
 * Exposed table for the mariaDB table - group_settings. Append-only: every change is written as a
 * new record so an audit trail of setting changes is preserved. The latest record per (group_id,
 * setting_key) holds the current value; a NULL setting_value records a clear.
 */
@Suppress("MagicNumber")
object GroupSettingsTable : IdTable<Int>("group_settings") {
  val idColumn = integer("id").autoIncrement().entityId()
  val groupId = integer("group_id").index() // FK to groups.id (enforced at DB level)
  val settingKey = varchar("setting_key", 100).index()
  val settingValue = text("setting_value").nullable()
  val updatedBy =
      integer("updated_by").nullable().index() // FK to profile.id (enforced at DB level)
  val updatedAt = datetime("updated_at")

  override val id: Column<EntityID<Int>>
    get() = idColumn
}

class GroupSettingsDAO(id: EntityID<Int>) : Entity<Int>(id) {
  companion object : EntityClass<Int, GroupSettingsDAO>(GroupSettingsTable)

  var groupId by GroupSettingsTable.groupId
  var settingKey by GroupSettingsTable.settingKey
  var settingValue by GroupSettingsTable.settingValue
  var updatedBy by GroupSettingsTable.updatedBy
  var updatedAt by GroupSettingsTable.updatedAt
}

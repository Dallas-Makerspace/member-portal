package org.dallasmakerspace.config.db

import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.javatime.datetime

object ConfigOverrideTable : LongIdTable("config_overrides") {
  val configKey = varchar("config_key", 255).index()
  val configValue = text("config_value")
  val configType = varchar("config_type", 50)
  val changedBy = varchar("changed_by", 255)
  val changeReason = varchar("change_reason", 500)
  val changedAt = datetime("changed_at")
  val isReset = bool("is_reset").default(false)
  val infraOnly = bool("infra_only").default(false)
}

class ConfigOverrideDAO(id: EntityID<Long>) : LongEntity(id) {
  companion object : LongEntityClass<ConfigOverrideDAO>(ConfigOverrideTable)

  var configKey by ConfigOverrideTable.configKey
  var configValue by ConfigOverrideTable.configValue
  var configType by ConfigOverrideTable.configType
  var changedBy by ConfigOverrideTable.changedBy
  var changeReason by ConfigOverrideTable.changeReason
  var changedAt by ConfigOverrideTable.changedAt
  var isReset by ConfigOverrideTable.isReset
  var infraOnly by ConfigOverrideTable.infraOnly
}

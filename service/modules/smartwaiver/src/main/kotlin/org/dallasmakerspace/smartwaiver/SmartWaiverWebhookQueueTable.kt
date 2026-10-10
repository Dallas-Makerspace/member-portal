package org.dallasmakerspace.smartwaiver

import org.jetbrains.exposed.sql.Table

@Suppress("MagicNumber")
object SmartWaiverHooksTable : Table("`dms-makermanager`.smartwaiver_hooks") {
  val id = integer("id").autoIncrement()
  val hook = varchar("hook", 255)
  val uniqueId = varchar("unique_id", 255)
  val processedAt = varchar("processed_at", 30).nullable()
  val createdAt = varchar("created_at", 30).nullable()
  val updatedAt = varchar("updated_at", 30).nullable()
  override val primaryKey = PrimaryKey(id)
}

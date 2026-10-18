package org.dallasmakerspace.keyvalue

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime

/**
 * Exposed table for `key_value`: small string values addressed by (namespace, key). Keys and
 * namespaces are case-sensitive in the database (utf8mb4_bin), like map keys.
 */
object KeyValueTable : Table("key_value") {
  val namespace = varchar("namespace", KeyValueStore.MAX_NAMESPACE_LENGTH)
  val itemKey = varchar("item_key", KeyValueStore.MAX_KEY_LENGTH)
  val itemValue = text("item_value")
  val updatedAt = datetime("updated_at")

  override val primaryKey = PrimaryKey(namespace, itemKey)
}

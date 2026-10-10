package org.dallasmakerspace.shortlinks.db

import java.time.Instant
import kotlinx.coroutines.Dispatchers
import org.dallasmakerspace.core.DBMemberPortalConnection
import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

/** Exposed table for the mariaDB table - short_links_namespaces. */
object NamespaceTable : IdTable<Int>("short_links_namespaces") {
  val idColumn = integer("id").autoIncrement().entityId()
  val name = varchar("name", 100)
  val ownerType = varchar("owner_type", 20)
  val ownerGroupId = integer("owner_group_id").nullable()
  val description = text("description").nullable()
  val isActive = bool("is_active").default(true)
  val createdAt = timestamp("created_at").default(Instant.now())
  val createdBy = integer("created_by").nullable()

  override val id: Column<EntityID<Int>>
    get() = idColumn
}

class NamespaceDAO(id: EntityID<Int>) : Entity<Int>(id) {
  companion object : EntityClass<Int, NamespaceDAO>(NamespaceTable)

  var name by NamespaceTable.name
  var ownerType by NamespaceTable.ownerType
  var ownerGroupId by NamespaceTable.ownerGroupId
  var description by NamespaceTable.description
  var isActive by NamespaceTable.isActive
  var createdAt by NamespaceTable.createdAt
  var createdBy by NamespaceTable.createdBy
}

/** Exposed table for the mariaDB table - short_links_namespace_aliases. */
object NamespaceAliasTable : IdTable<Int>("short_links_namespace_aliases") {
  val idColumn = integer("id").autoIncrement().entityId()
  val namespaceId = reference("namespace_id", NamespaceTable, onDelete = ReferenceOption.CASCADE)
  val alias = varchar("alias", 20).uniqueIndex()
  val isPrimary = bool("is_primary").default(false)
  val createdAt = timestamp("created_at").default(Instant.now())

  override val id: Column<EntityID<Int>>
    get() = idColumn
}

class NamespaceAliasDAO(id: EntityID<Int>) : Entity<Int>(id) {
  companion object : EntityClass<Int, NamespaceAliasDAO>(NamespaceAliasTable)

  var namespaceId by NamespaceAliasTable.namespaceId
  var alias by NamespaceAliasTable.alias
  var isPrimary by NamespaceAliasTable.isPrimary
  var createdAt by NamespaceAliasTable.createdAt

  var namespace by NamespaceDAO referencedOn NamespaceAliasTable.namespaceId
}

/** Exposed table for the mariaDB table - short_links. */
object ShortLinkTable : IdTable<Int>("short_links") {
  val idColumn = integer("id").autoIncrement().entityId()
  val namespaceId =
      reference("namespace_id", NamespaceTable, onDelete = ReferenceOption.CASCADE).nullable()
  val slug = varchar("slug", 100)
  val redirectType = varchar("redirect_type", 20)
  val destinationUrl = varchar("destination_url", 2048)
  val description = text("description").nullable()
  val creatorId = integer("creator_id").nullable()
  val updatedBy = integer("updated_by").nullable()
  val isActive = bool("is_active").default(true)
  val createdAt = timestamp("created_at").default(Instant.now())
  val updatedAt = timestamp("updated_at").default(Instant.now())

  override val id: Column<EntityID<Int>>
    get() = idColumn
}

class ShortLinkDAO(id: EntityID<Int>) : Entity<Int>(id) {
  companion object : EntityClass<Int, ShortLinkDAO>(ShortLinkTable)

  var namespaceId by ShortLinkTable.namespaceId
  var slug by ShortLinkTable.slug
  var redirectType by ShortLinkTable.redirectType
  var destinationUrl by ShortLinkTable.destinationUrl
  var description by ShortLinkTable.description
  var creatorId by ShortLinkTable.creatorId
  var updatedBy by ShortLinkTable.updatedBy
  var isActive by ShortLinkTable.isActive
  var createdAt by ShortLinkTable.createdAt
  var updatedAt by ShortLinkTable.updatedAt

  var namespace by NamespaceDAO optionalReferencedOn ShortLinkTable.namespaceId
}

/** Exposed table for the mariaDB table - short_links_clicks. */
object ShortLinkClickTable : IdTable<Long>("short_links_clicks") {
  val idColumn = long("id").autoIncrement().entityId()
  val shortLinkId = reference("short_link_id", ShortLinkTable, onDelete = ReferenceOption.CASCADE)
  val memberId = integer("member_id").nullable()
  val resolvedPath = varchar("resolved_path", 255).nullable()
  val variableValues = text("variable_values").nullable()
  val clickedAt = timestamp("clicked_at").default(Instant.now())
  val ipAddress = varchar("ip_address", 45).nullable()
  val userAgent = varchar("user_agent", 500).nullable()
  val referrer = varchar("referrer", 2048).nullable()

  override val id: Column<EntityID<Long>>
    get() = idColumn
}

class ShortLinkClickDAO(id: EntityID<Long>) : Entity<Long>(id) {
  companion object : EntityClass<Long, ShortLinkClickDAO>(ShortLinkClickTable)

  var shortLinkId by ShortLinkClickTable.shortLinkId
  var memberId by ShortLinkClickTable.memberId
  var resolvedPath by ShortLinkClickTable.resolvedPath
  var variableValues by ShortLinkClickTable.variableValues
  var clickedAt by ShortLinkClickTable.clickedAt
  var ipAddress by ShortLinkClickTable.ipAddress
  var userAgent by ShortLinkClickTable.userAgent
  var referrer by ShortLinkClickTable.referrer

  var shortLink by ShortLinkDAO referencedOn ShortLinkClickTable.shortLinkId
}

/** Exposed table for the mariaDB table - short_links_reserved_aliases. */
object ReservedAliasTable : Table("short_links_reserved_aliases") {
  val alias = varchar("alias", 20)
  val reason = varchar("reason", 255).nullable()
  val createdAt = timestamp("created_at").default(Instant.now())

  override val primaryKey = PrimaryKey(alias)
}

/** Suspend transaction helper for all short links database operations. */
suspend fun <T> suspendTransaction(block: Transaction.() -> T): T =
    newSuspendedTransaction(Dispatchers.IO, statement = block, db = DBMemberPortalConnection.db)

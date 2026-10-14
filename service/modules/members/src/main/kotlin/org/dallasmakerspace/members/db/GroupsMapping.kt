package org.dallasmakerspace.members.db

import kotlinx.datetime.toKotlinLocalDateTime
import org.dallasmakerspace.models.Group
import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.javatime.datetime

/** Exposed table for the mariaDB table - groups. */
object GroupsTable : IdTable<Int>("groups") {
  val idColumn = integer("id").autoIncrement().entityId()
  val name = varchar("name", 255)
  val dn = varchar("dn", 512)
  val created = datetime("created")
  val objectGuid = varchar("object_guid", 36).nullable()
  val status = varchar("status", 16).default("ACTIVE")
  val archivedAt = datetime("archived_at").nullable()
  val archivedBy = varchar("archived_by", 100).nullable()
  val archiveReason = varchar("archive_reason", 255).nullable()
  val archiveRunId = long("archive_run_id").nullable()

  override val id: Column<EntityID<Int>>
    get() = idColumn
}

class GroupDAO(id: EntityID<Int>) : Entity<Int>(id) {
  companion object : EntityClass<Int, GroupDAO>(GroupsTable)

  var name by GroupsTable.name
  var dn by GroupsTable.dn
  var created by GroupsTable.created
  var objectGuid by GroupsTable.objectGuid
  var status by GroupsTable.status
  var archivedAt by GroupsTable.archivedAt
  var archivedBy by GroupsTable.archivedBy
  var archiveReason by GroupsTable.archiveReason
  var archiveRunId by GroupsTable.archiveRunId
}

fun daoToGroupModel(resultRow: ResultRow) =
    Group(
        resultRow[GroupsTable.idColumn].value,
        resultRow[GroupsTable.name],
        resultRow[GroupsTable.dn],
        resultRow[GroupsTable.created].toKotlinLocalDateTime(),
        resultRow[GroupsTable.objectGuid],
        resultRow[GroupsTable.status],
        resultRow[GroupsTable.archivedAt]?.toKotlinLocalDateTime())

package org.dallasmakerspace.members.db

import kotlinx.datetime.toKotlinLocalDateTime
import org.dallasmakerspace.models.ActivityLog
import org.dallasmakerspace.models.ActivityLogEvent
import org.dallasmakerspace.models.ActivityLogSource
import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.javatime.datetime

/** Exposed table for the mariaDB table - activity_log. */
object ActivityLogTable : IdTable<Int>("activity_log") {
  val idColumn = integer("id").autoIncrement().entityId()
  val sourceColumn = integer("source")
  val actorProfileRowId =
      integer("actor_profile_row_id").references(ProfileTable.idColumn).nullable()
  val subjectProfileRowId = integer("subject_profile_row_id").references(ProfileTable.idColumn)
  val event = integer("event")
  val attributes = text("attributes").nullable()
  val created = datetime("created").nullable()

  override val id: Column<EntityID<Int>>
    get() = idColumn
}

class ActivityLogDAO(id: EntityID<Int>) : Entity<Int>(id) {
  companion object : EntityClass<Int, ActivityLogDAO>(ActivityLogTable)

  var source by ActivityLogTable.sourceColumn
  var actorProfileRowId by ActivityLogTable.actorProfileRowId
  var subjectProfileRowId by ActivityLogTable.subjectProfileRowId
  var event by ActivityLogTable.event
  var attributes by ActivityLogTable.attributes
  var created by ActivityLogTable.created
}

fun daoToActivityLogModel(resultRow: ResultRow) =
    ActivityLog(
        resultRow[ActivityLogTable.idColumn].value,
        resultRow[ActivityLogTable.sourceColumn].let { ActivityLogSource.entries[it] },
        resultRow[ProfileColumnAliases.actorProfileAlias[ProfileTable.username]].toString(),
        resultRow[ProfileColumnAliases.subjectProfileAlias[ProfileTable.username]].toString(),
        resultRow[ActivityLogTable.event].let { ActivityLogEvent.entries[it] },
        resultRow[ActivityLogTable.attributes],
        resultRow[ActivityLogTable.created]?.toKotlinLocalDateTime())

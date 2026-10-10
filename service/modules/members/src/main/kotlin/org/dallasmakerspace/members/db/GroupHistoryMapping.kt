package org.dallasmakerspace.members.db

import java.time.ZoneId
import kotlinx.datetime.toKotlinLocalDateTime
import org.dallasmakerspace.models.ActionType
import org.dallasmakerspace.models.GroupHistory
import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.alias
import org.jetbrains.exposed.sql.javatime.datetime

private val UTC_ZONE = ZoneId.of("UTC")
private val CHICAGO_ZONE = ZoneId.of("America/Chicago")

/** Exposed table for the mariaDB table - group_history. */
object GroupHistoryTable : IdTable<Int>("group_history") {
  val idColumn = integer("id").autoIncrement().entityId()
  val actorId = integer("actor_id").references(ProfileTable.idColumn)
  val memberId = integer("member_id").references(ProfileTable.idColumn)
  val groupId = integer("group_id").references(GroupsTable.idColumn)
  val actionType = integer("action_type")
  val eventTimestamp = datetime("event_timestamp")
  val created = datetime("created")
  val changeSource = varchar("source", 20).default("AD_WEBHOOK")

  override val id: Column<EntityID<Int>>
    get() = idColumn
}

class GroupHistoryDAO(id: EntityID<Int>) : Entity<Int>(id) {
  companion object : EntityClass<Int, GroupHistoryDAO>(GroupHistoryTable)

  var actorId by GroupHistoryTable.actorId
  var memberId by GroupHistoryTable.memberId
  var groupId by GroupHistoryTable.groupId
  var actionType by GroupHistoryTable.actionType
  var eventTimestamp by GroupHistoryTable.eventTimestamp
  var created by GroupHistoryTable.created
  var changeSource by GroupHistoryTable.changeSource
}

/** Column aliases to handle joins for profile references */
object GroupHistoryColumnAliases {
  val actorProfileAlias = ProfileTable.alias("actor_profile")
  val memberProfileAlias = ProfileTable.alias("member_profile")
  val groupsAlias = GroupsTable.alias("group")
}

fun daoToGroupHistoryModel(resultRow: ResultRow): GroupHistory {
  // Convert UTC timestamp to Chicago timezone
  val utcTimestamp = resultRow[GroupHistoryTable.eventTimestamp]
  val chicagoTimestamp =
      utcTimestamp.atZone(UTC_ZONE).withZoneSameInstant(CHICAGO_ZONE).toLocalDateTime()

  return GroupHistory(
      resultRow[GroupHistoryColumnAliases.actorProfileAlias[ProfileTable.username]].toString(),
      resultRow[GroupHistoryColumnAliases.memberProfileAlias[ProfileTable.username]].toString(),
      ActionType.fromValue(resultRow[GroupHistoryTable.actionType]),
      chicagoTimestamp.toKotlinLocalDateTime(),
  )
}

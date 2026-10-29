package org.dallasmakerspace.agenttools

import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton
import org.dallasmakerspace.core.db.suspendTransaction
import org.dallasmakerspace.members.GROUP_STATUS_ARCHIVED
import org.dallasmakerspace.members.db.ActivityLogTable
import org.dallasmakerspace.members.db.GroupHistoryColumnAliases.actorProfileAlias as groupActor
import org.dallasmakerspace.members.db.GroupHistoryColumnAliases.groupsAlias
import org.dallasmakerspace.members.db.GroupHistoryColumnAliases.memberProfileAlias
import org.dallasmakerspace.members.db.GroupHistoryTable
import org.dallasmakerspace.members.db.GroupsTable
import org.dallasmakerspace.members.db.ProfileColumnAliases.actorProfileAlias as activityActor
import org.dallasmakerspace.members.db.ProfileColumnAliases.subjectProfileAlias
import org.dallasmakerspace.members.db.ProfileTable
import org.dallasmakerspace.models.ActionType
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select

/** A group membership change; [atUtc] is as stored (UTC). */
data class GroupChangeRow(
    val actor: String,
    val member: String,
    val group: String,
    val added: Boolean,
    val atUtc: LocalDateTime,
)

/** An activity log entry; [event] and [source] are the stored numbers. */
data class ActivityRow(
    val actor: String?,
    val event: Int,
    val source: Int,
    val attributes: String?,
    val atUtc: LocalDateTime?,
)

/**
 * Bounded, read-only audit reads. The existing history and activity services return every row
 * (or omit the group name) and the activity read inserts a profile for an unknown username, so
 * the audit tool reads through these instead.
 */
@Singleton
open class AuditTrailQueries @Inject constructor() {
  /** Newest first; [member] and [group] each narrow the result when given. */
  open suspend fun groupChanges(
      member: String?,
      group: String?,
      sinceUtc: LocalDateTime,
      limit: Int,
  ): List<GroupChangeRow> = suspendTransaction {
    var condition: Op<Boolean> = GroupHistoryTable.eventTimestamp greaterEq sinceUtc
    if (member != null) condition = condition and (memberProfileAlias[ProfileTable.username] eq member)
    if (group != null) {
      condition =
          condition and
              (groupsAlias[GroupsTable.name] eq group) and
              (groupsAlias[GroupsTable.status] neq GROUP_STATUS_ARCHIVED)
    }
    GroupHistoryTable.join(
            groupActor, JoinType.INNER, GroupHistoryTable.actorId, groupActor[ProfileTable.idColumn])
        .join(
            memberProfileAlias,
            JoinType.INNER,
            GroupHistoryTable.memberId,
            memberProfileAlias[ProfileTable.idColumn])
        .join(
            groupsAlias,
            JoinType.INNER,
            GroupHistoryTable.groupId,
            groupsAlias[GroupsTable.idColumn])
        .select { condition }
        .orderBy(GroupHistoryTable.eventTimestamp, SortOrder.DESC)
        .limit(limit)
        .map {
          GroupChangeRow(
              actor = it[groupActor[ProfileTable.username]].value,
              member = it[memberProfileAlias[ProfileTable.username]].value,
              group = it[groupsAlias[GroupsTable.name]],
              added = it[GroupHistoryTable.actionType] == ActionType.ADD_USER.value,
              atUtc = it[GroupHistoryTable.eventTimestamp],
          )
        }
  }

  /** Newest first. */
  open suspend fun activity(member: String, sinceUtc: LocalDateTime, limit: Int): List<ActivityRow> =
      suspendTransaction {
        ActivityLogTable.join(
                subjectProfileAlias,
                JoinType.INNER,
                ActivityLogTable.subjectProfileRowId,
                subjectProfileAlias[ProfileTable.idColumn])
            .join(
                activityActor,
                JoinType.LEFT,
                ActivityLogTable.actorProfileRowId,
                activityActor[ProfileTable.idColumn])
            .select {
              (subjectProfileAlias[ProfileTable.username] eq member) and
                  (ActivityLogTable.created greaterEq sinceUtc)
            }
            .orderBy(ActivityLogTable.created, SortOrder.DESC)
            .limit(limit)
            .map {
              ActivityRow(
                  actor = it.getOrNull(activityActor[ProfileTable.username])?.value,
                  event = it[ActivityLogTable.event],
                  source = it[ActivityLogTable.sourceColumn],
                  attributes = it[ActivityLogTable.attributes],
                  atUtc = it[ActivityLogTable.created],
              )
            }
      }

  open suspend fun hasProfile(username: String): Boolean = suspendTransaction {
    !ProfileTable.select { ProfileTable.username eq username }.limit(1).empty()
  }
}

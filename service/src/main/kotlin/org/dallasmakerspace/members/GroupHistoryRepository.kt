package org.dallasmakerspace.members

import java.time.LocalDateTime
import javax.inject.Inject
import org.dallasmakerspace.core.db.suspendTransaction
import org.dallasmakerspace.members.db.GroupDAO
import org.dallasmakerspace.members.db.GroupHistoryColumnAliases.actorProfileAlias
import org.dallasmakerspace.members.db.GroupHistoryColumnAliases.groupsAlias
import org.dallasmakerspace.members.db.GroupHistoryColumnAliases.memberProfileAlias
import org.dallasmakerspace.members.db.GroupHistoryDAO
import org.dallasmakerspace.members.db.GroupHistoryTable
import org.dallasmakerspace.members.db.GroupsTable
import org.dallasmakerspace.members.db.ProfileTable
import org.dallasmakerspace.members.db.daoToGroupHistoryModel
import org.dallasmakerspace.models.ActionType
import org.dallasmakerspace.models.GroupHistory
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.slf4j.LoggerFactory

/** Manages the group history records. CRUD operations using GroupHistoryDAO. */
class GroupHistoryRepository @Inject constructor() : IGroupHistoryRepository {
  private val logger = LoggerFactory.getLogger(GroupHistoryRepository::class.java)

  /**
   * Fetches the group history records for a specific group.
   *
   * @param groupId The ID of the group.
   * @return List of group history records.
   */
  override suspend fun getGroupHistoryById(groupId: Int): List<GroupHistory> = suspendTransaction {
    GroupHistoryTable.join(
            actorProfileAlias,
            JoinType.INNER,
            GroupHistoryTable.actorId,
            actorProfileAlias[ProfileTable.idColumn],
        )
        .join(
            memberProfileAlias,
            JoinType.INNER,
            GroupHistoryTable.memberId,
            memberProfileAlias[ProfileTable.idColumn],
        )
        .join(
            groupsAlias,
            JoinType.INNER,
            GroupHistoryTable.groupId,
            groupsAlias[GroupsTable.idColumn],
        )
        .select { GroupHistoryTable.groupId eq groupId }
        .orderBy(GroupHistoryTable.eventTimestamp, SortOrder.DESC)
        .map { daoToGroupHistoryModel(it) }
  }

  /**
   * Fetches the group history records by group name.
   *
   * @param groupName The name of the group.
   * @return List of group history records from the last 6 months.
   */
  override suspend fun getGroupHistoryByName(groupName: String): List<GroupHistory> =
      suspendTransaction {
        val sixMonthsAgo = LocalDateTime.now().minusMonths(6)

        GroupHistoryTable.join(
                actorProfileAlias,
                JoinType.INNER,
                GroupHistoryTable.actorId,
                actorProfileAlias[ProfileTable.idColumn],
            )
            .join(
                memberProfileAlias,
                JoinType.INNER,
                GroupHistoryTable.memberId,
                memberProfileAlias[ProfileTable.idColumn],
            )
            .join(
                groupsAlias,
                JoinType.INNER,
                GroupHistoryTable.groupId,
                groupsAlias[GroupsTable.idColumn],
            )
            .select {
              (groupsAlias[GroupsTable.name] eq groupName) and
                  (GroupHistoryTable.eventTimestamp greaterEq sixMonthsAgo)
            }
            .orderBy(GroupHistoryTable.eventTimestamp, SortOrder.DESC)
            .map { daoToGroupHistoryModel(it) }
      }

  /**
   * Fetches the group history records for a specific member.
   *
   * @param memberId The ID of the member.
   * @return List of group history records.
   */
  override suspend fun getMemberGroupHistoryById(memberId: Int): List<GroupHistory> =
      suspendTransaction {
        GroupHistoryTable.join(
                actorProfileAlias,
                JoinType.INNER,
                GroupHistoryTable.actorId,
                actorProfileAlias[ProfileTable.idColumn],
            )
            .join(
                memberProfileAlias,
                JoinType.INNER,
                GroupHistoryTable.memberId,
                memberProfileAlias[ProfileTable.idColumn],
            )
            .join(
                groupsAlias,
                JoinType.INNER,
                GroupHistoryTable.groupId,
                groupsAlias[GroupsTable.idColumn],
            )
            .select { GroupHistoryTable.memberId eq memberId }
            .orderBy(GroupHistoryTable.eventTimestamp, SortOrder.DESC)
            .map { daoToGroupHistoryModel(it) }
      }

  /**
   * Fetches the group history records by member username.
   *
   * @param username The username of the member.
   * @return List of group history records.
   */
  override suspend fun getMemberGroupHistoryByUsername(username: String): List<GroupHistory> =
      suspendTransaction {
        GroupHistoryTable.join(
                actorProfileAlias,
                JoinType.INNER,
                GroupHistoryTable.actorId,
                actorProfileAlias[ProfileTable.idColumn],
            )
            .join(
                memberProfileAlias,
                JoinType.INNER,
                GroupHistoryTable.memberId,
                memberProfileAlias[ProfileTable.idColumn],
            )
            .join(
                groupsAlias,
                JoinType.INNER,
                GroupHistoryTable.groupId,
                groupsAlias[GroupsTable.idColumn],
            )
            .select { memberProfileAlias[ProfileTable.username] eq username }
            .orderBy(GroupHistoryTable.eventTimestamp, SortOrder.DESC)
            .map { daoToGroupHistoryModel(it) }
      }

  /**
   * Inserts a new group history record.
   *
   * @param actorId The ID of the user who performed the action.
   * @param memberId The ID of the member affected by the action.
   * @param groupId The ID of the group that was modified.
   * @param actionType The type of action (ADD or REMOVE).
   * @param eventTimestamp The timestamp when the event occurred.
   * @return The inserted group history record.
   */
  override suspend fun insertGroupHistory(
      actorId: Int,
      memberId: Int,
      groupId: Int,
      actionType: ActionType,
      eventTimestamp: java.time.LocalDateTime,
      source: String,
  ): GroupHistory = suspendTransaction {
    val historyDao =
        GroupHistoryDAO.new {
          this.actorId = actorId
          this.memberId = memberId
          this.groupId = groupId
          this.actionType = actionType.value
          this.eventTimestamp = eventTimestamp
          this.created = LocalDateTime.now()
          this.changeSource = source
        }

    // Fetch the complete record with joined data
    GroupHistoryTable.join(
            actorProfileAlias,
            JoinType.INNER,
            GroupHistoryTable.actorId,
            actorProfileAlias[ProfileTable.idColumn],
        )
        .join(
            memberProfileAlias,
            JoinType.INNER,
            GroupHistoryTable.memberId,
            memberProfileAlias[ProfileTable.idColumn],
        )
        .join(
            groupsAlias,
            JoinType.INNER,
            GroupHistoryTable.groupId,
            groupsAlias[GroupsTable.idColumn],
        )
        .select { GroupHistoryTable.idColumn eq historyDao.id }
        .map { daoToGroupHistoryModel(it) }
        .first()
  }

  /**
   * Looks up or creates a group in the groups table.
   *
   * @param name The name of the group.
   * @param dn The distinguished name of the group (optional).
   * @return The ID of the group.
   */
  override suspend fun getOrCreateGroup(name: String, dn: String?): Int = suspendTransaction {
    val existingGroup = GroupDAO.find { GroupsTable.name eq name }.firstOrNull()

    if (existingGroup != null) {
      existingGroup.id.value
    } else {
      val actualDn = dn ?: "CN=$name,OU=Groups,DC=dms,DC=local"
      GroupDAO.new {
            this.name = name
            this.dn = actualDn
            this.created = LocalDateTime.now()
          }
          .id
          .value
    }
  }

  override suspend fun hasPortalInitiatedRecord(
      memberId: Int,
      groupId: Int,
      actionType: ActionType,
      windowStart: java.time.LocalDateTime,
      windowEnd: java.time.LocalDateTime,
  ): Boolean = suspendTransaction {
    GroupHistoryTable.select {
          (GroupHistoryTable.memberId eq memberId) and
              (GroupHistoryTable.groupId eq groupId) and
              (GroupHistoryTable.actionType eq actionType.value) and
              (GroupHistoryTable.changeSource eq "PORTAL") and
              (GroupHistoryTable.eventTimestamp greaterEq windowStart) and
              (GroupHistoryTable.eventTimestamp lessEq windowEnd)
        }
        .count() > 0
  }
}

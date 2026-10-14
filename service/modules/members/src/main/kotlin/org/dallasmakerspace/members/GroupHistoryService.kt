package org.dallasmakerspace.members

import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.toJavaLocalDateTime
import org.dallasmakerspace.activedirectory.ActiveDirectoryService
import org.dallasmakerspace.core.db.suspendTransaction
import org.dallasmakerspace.members.db.GroupDAO
import org.dallasmakerspace.members.db.GroupHistoryColumnAliases
import org.dallasmakerspace.members.db.GroupHistoryDAO
import org.dallasmakerspace.members.db.GroupHistoryTable
import org.dallasmakerspace.members.db.GroupsTable
import org.dallasmakerspace.members.db.ProfileTable
import org.dallasmakerspace.members.db.daoToGroupHistoryModel
import org.dallasmakerspace.models.DMSMember
import org.dallasmakerspace.models.GroupHistory
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

/** Service for managing group history operations. */
@Singleton
class GroupHistoryService
@Inject
constructor(
    private val memberService: MemberService,
    private val activeDirectoryService: ActiveDirectoryService,
) {
  private val logger = LoggerFactory.getLogger(GroupHistoryService::class.java)

  /**
   * Records a group history event.
   *
   * @param actorUsername The username of the user who performed the action
   * @param memberDn The distinguished name of the member affected by the action
   * @param groupName The name of the group
   * @param eventTimestamp The timestamp when the event occurred
   * @return True if the operation was successful, false otherwise
   */
  suspend fun recordGroupHistoryEvent(
      actorUsername: String,
      memberDn: String,
      groupName: String,
      eventTimestamp: kotlinx.datetime.LocalDateTime,
  ): Boolean {
    try {
      // Get member username from DN by querying Active Directory
      val adUsers = withContext(Dispatchers.IO) { activeDirectoryService.getMembersByDnList(listOf(memberDn)) }
      if (adUsers.isEmpty()) {
        logger.error("Could not find user with DN: $memberDn")
        return false
      }
      val memberUsername = adUsers[0].sAMAccountName

      // Get actor and member profiles outside the transaction
      val actorProfile: DMSMember
      val memberProfile: DMSMember

      try {
        actorProfile = memberService.getMemberByUsername(actorUsername)
      } catch (e: Exception) {
        logger.error("Actor not found: $actorUsername", e)
        return false
      }

      try {
        memberProfile = memberService.getMemberByUsername(memberUsername)
      } catch (e: Exception) {
        logger.error("Member not found: $memberUsername", e)
        return false
      }

      val actorId = actorProfile.id
      val memberId = memberProfile.id

      return suspendTransaction {
        // Get or create group
        val group =
            GroupDAO.find {
                  (GroupsTable.name eq groupName) and
                      (GroupsTable.status neq GROUP_STATUS_ARCHIVED)
                }
                .firstOrNull()
                ?: GroupDAO.new {
                  name = groupName
                  // Store the full DN if available, otherwise use a constructed one
                  dn = "CN=$groupName,OU=Groups,DC=dms,DC=local"
                  created = LocalDateTime.now()
                }

        // Record the history event
        GroupHistoryDAO.new {
          this.actorId = actorId
          this.memberId = memberId
          this.groupId = group.id.value
          this.eventTimestamp = eventTimestamp.toJavaLocalDateTime()
          this.created = LocalDateTime.now()
        }

        true
      }
    } catch (e: Exception) {
      logger.error("Error recording group history event", e)
      return false
    }
  }

  /**
   * Gets the history for a specific group.
   *
   * @param groupName The name of the group
   * @return A list of group history events from the last 6 months
   */
  fun getGroupHistory(groupName: String): List<GroupHistory> {
    return transaction {
      val actorProfileAlias = GroupHistoryColumnAliases.actorProfileAlias
      val memberProfileAlias = GroupHistoryColumnAliases.memberProfileAlias
      val groupsAlias = GroupHistoryColumnAliases.groupsAlias
      val sixMonthsAgo = LocalDateTime.now().minusMonths(6)

      (GroupHistoryTable.join(
                  actorProfileAlias,
                  JoinType.INNER,
                  additionalConstraint = {
                    GroupHistoryTable.actorId eq actorProfileAlias[ProfileTable.idColumn]
                  },
              )
              .join(
                  memberProfileAlias,
                  JoinType.INNER,
                  additionalConstraint = {
                    GroupHistoryTable.memberId eq memberProfileAlias[ProfileTable.idColumn]
                  },
              )
              .join(
                  groupsAlias,
                  JoinType.INNER,
                  additionalConstraint = {
                    GroupHistoryTable.groupId eq groupsAlias[GroupsTable.idColumn]
                  },
              ))
          .slice(
              actorProfileAlias[ProfileTable.username],
              memberProfileAlias[ProfileTable.username],
              GroupHistoryTable.eventTimestamp,
          )
          .select {
            (groupsAlias[GroupsTable.name] eq groupName) and
                (groupsAlias[GroupsTable.status] neq GROUP_STATUS_ARCHIVED) and
                (GroupHistoryTable.eventTimestamp greaterEq sixMonthsAgo)
          }
          .orderBy(GroupHistoryTable.eventTimestamp, SortOrder.DESC)
          .map { daoToGroupHistoryModel(it) }
    }
  }

  /**
   * Gets the history for a specific member.
   *
   * @param username The username of the member
   * @return A list of group history events from the last 6 months
   */
  fun getMemberGroupHistory(username: String): List<GroupHistory> {
    return transaction {
      val actorProfileAlias = GroupHistoryColumnAliases.actorProfileAlias
      val memberProfileAlias = GroupHistoryColumnAliases.memberProfileAlias
      val groupsAlias = GroupHistoryColumnAliases.groupsAlias
      val sixMonthsAgo = LocalDateTime.now().minusMonths(6)

      (GroupHistoryTable.join(
                  actorProfileAlias,
                  JoinType.INNER,
                  additionalConstraint = {
                    GroupHistoryTable.actorId eq actorProfileAlias[ProfileTable.idColumn]
                  },
              )
              .join(
                  memberProfileAlias,
                  JoinType.INNER,
                  additionalConstraint = {
                    GroupHistoryTable.memberId eq memberProfileAlias[ProfileTable.idColumn]
                  },
              )
              .join(
                  groupsAlias,
                  JoinType.INNER,
                  additionalConstraint = {
                    GroupHistoryTable.groupId eq groupsAlias[GroupsTable.idColumn]
                  },
              ))
          .slice(
              actorProfileAlias[ProfileTable.username],
              memberProfileAlias[ProfileTable.username],
              GroupHistoryTable.eventTimestamp,
          )
          .select {
            (memberProfileAlias[ProfileTable.username] eq username) and
                (GroupHistoryTable.eventTimestamp greaterEq sixMonthsAgo)
          }
          .orderBy(GroupHistoryTable.eventTimestamp, SortOrder.DESC)
          .map { daoToGroupHistoryModel(it) }
    }
  }
}

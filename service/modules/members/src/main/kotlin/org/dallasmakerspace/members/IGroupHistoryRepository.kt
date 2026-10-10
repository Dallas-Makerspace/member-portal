package org.dallasmakerspace.members

import org.dallasmakerspace.models.ActionType
import org.dallasmakerspace.models.GroupHistory

interface IGroupHistoryRepository {
  suspend fun getGroupHistoryById(groupId: Int): List<GroupHistory>

  suspend fun getGroupHistoryByName(groupName: String): List<GroupHistory>

  suspend fun getMemberGroupHistoryById(memberId: Int): List<GroupHistory>

  suspend fun getMemberGroupHistoryByUsername(username: String): List<GroupHistory>

  suspend fun insertGroupHistory(
      actorId: Int,
      memberId: Int,
      groupId: Int,
      actionType: ActionType,
      eventTimestamp: java.time.LocalDateTime,
      source: String = "AD_WEBHOOK",
  ): GroupHistory

  suspend fun getOrCreateGroup(name: String, dn: String? = null): Int

  suspend fun hasPortalInitiatedRecord(
      memberId: Int,
      groupId: Int,
      actionType: ActionType,
      windowStart: java.time.LocalDateTime,
      windowEnd: java.time.LocalDateTime,
  ): Boolean
}

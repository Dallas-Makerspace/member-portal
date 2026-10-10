package org.dallasmakerspace.members

import javax.inject.Inject
import org.dallasmakerspace.models.ActionType
import org.dallasmakerspace.models.GroupHistory

class GroupHistoryRepositoryMock @Inject constructor() : IGroupHistoryRepository {

  override suspend fun getGroupHistoryById(groupId: Int): List<GroupHistory> = emptyList()

  override suspend fun getGroupHistoryByName(groupName: String): List<GroupHistory> = emptyList()

  override suspend fun getMemberGroupHistoryById(memberId: Int): List<GroupHistory> = emptyList()

  override suspend fun getMemberGroupHistoryByUsername(username: String): List<GroupHistory> =
      emptyList()

  override suspend fun insertGroupHistory(
      actorId: Int,
      memberId: Int,
      groupId: Int,
      actionType: ActionType,
      eventTimestamp: java.time.LocalDateTime,
      source: String,
  ): GroupHistory = throw UnsupportedOperationException("Mock: insertGroupHistory not supported")

  override suspend fun getOrCreateGroup(name: String, dn: String?): Int = 0

  override suspend fun hasPortalInitiatedRecord(
      memberId: Int,
      groupId: Int,
      actionType: ActionType,
      windowStart: java.time.LocalDateTime,
      windowEnd: java.time.LocalDateTime,
  ): Boolean = false
}

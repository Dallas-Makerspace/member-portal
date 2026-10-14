package org.dallasmakerspace.members

import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.core.db.suspendTransaction
import org.dallasmakerspace.members.db.GroupDAO
import org.dallasmakerspace.members.db.GroupSettingsDAO
import org.dallasmakerspace.members.db.GroupSettingsTable
import org.dallasmakerspace.members.db.GroupsTable
import org.dallasmakerspace.members.db.ProfileDAO
import org.dallasmakerspace.members.db.ProfileTable
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and

class GroupSettingsRepository
@Inject
constructor(
    loggerFactory: LoggerFactory,
    private val groupHistoryRepository: IGroupHistoryRepository,
) : IGroupSettingsRepository {
  private val log = loggerFactory.create(javaClass)

  private val utcZone = ZoneId.of("UTC")

  override suspend fun getGroupId(name: String): Int? = suspendTransaction {
    GroupDAO.find {
          (GroupsTable.name eq name) and (GroupsTable.status neq GROUP_STATUS_ARCHIVED)
        }
        .firstOrNull()
        ?.id
        ?.value
  }

  override suspend fun getOrCreateGroupId(name: String, dn: String?): Int =
      groupHistoryRepository.getOrCreateGroup(name, dn)

  override suspend fun getProfileId(username: String): Int? = suspendTransaction {
    ProfileDAO.findById(EntityID(username, ProfileTable))?.idColumn?.value
  }

  override suspend fun getLatest(groupId: Int): Map<String, String> = suspendTransaction {
    GroupSettingsDAO.find { GroupSettingsTable.groupId eq groupId }
        .orderBy(GroupSettingsTable.idColumn to SortOrder.DESC)
        .map { dao -> dao.settingKey to dao.settingValue }
        .distinctBy { it.first }
        .mapNotNull { (key, value) -> value?.let { key to it } }
        .toMap()
  }

  override suspend fun getHistory(groupId: Int): List<GroupSettingsRecord> = suspendTransaction {
    GroupSettingsDAO.find { GroupSettingsTable.groupId eq groupId }
        .orderBy(GroupSettingsTable.idColumn to SortOrder.DESC)
        .map { dao ->
          GroupSettingsRecord(
              key = dao.settingKey,
              value = dao.settingValue,
              updatedByProfileId = dao.updatedBy,
              updatedAt = dao.updatedAt,
          )
        }
  }

  override suspend fun getUsername(profileId: Int): String? = suspendTransaction {
    ProfileDAO.find { ProfileTable.idColumn eq profileId }.firstOrNull()?.let { it.id.value }
  }

  override suspend fun getUsernames(profileIds: Collection<Int>): Map<Int, String> {
    if (profileIds.isEmpty()) return emptyMap()
    return suspendTransaction {
      ProfileDAO.find { ProfileTable.idColumn inList profileIds }
          .associate { dao -> dao.idColumn.value to dao.id.value }
    }
  }

  override suspend fun append(
      groupId: Int,
      key: String,
      value: String?,
      updatedByProfileId: Int?,
  ) {
    suspendTransaction {
      GroupSettingsDAO.new {
        this.groupId = groupId
        this.settingKey = key
        this.settingValue = value
        this.updatedBy = updatedByProfileId
        this.updatedAt = LocalDateTime.now(utcZone)
      }
    }
    log.info("Appended group setting '$key' for group $groupId (value=$value)")
  }
}

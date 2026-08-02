package org.dallasmakerspace.members

import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.members.db.MemberSettingsDAO
import org.dallasmakerspace.members.db.MemberSettingsTable
import org.dallasmakerspace.members.db.ProfileDAO
import org.dallasmakerspace.members.db.ProfileTable
import org.dallasmakerspace.members.db.suspendTransaction
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.SortOrder

class MemberSettingsRepository @Inject constructor(loggerFactory: LoggerFactory) :
    IMemberSettingsRepository {
  private val log = loggerFactory.create(javaClass)

  private val utcZone = ZoneId.of("UTC")

  override suspend fun getProfileId(username: String): Int? = suspendTransaction {
    ProfileDAO.findById(EntityID(username, ProfileTable))?.idColumn?.value
  }

  override suspend fun getLatest(profileId: Int): Map<String, String> = suspendTransaction {
    MemberSettingsDAO.find { MemberSettingsTable.profileId eq profileId }
        .orderBy(MemberSettingsTable.idColumn to SortOrder.DESC)
        .map { dao -> dao.settingKey to dao.settingValue }
        .distinctBy { it.first }
        .mapNotNull { (key, value) -> value?.let { key to it } }
        .toMap()
  }

  override suspend fun getHistory(profileId: Int): List<MemberSettingsRecord> = suspendTransaction {
    MemberSettingsDAO.find { MemberSettingsTable.profileId eq profileId }
        .orderBy(MemberSettingsTable.idColumn to SortOrder.DESC)
        .map { dao ->
          MemberSettingsRecord(
              key = dao.settingKey,
              value = dao.settingValue,
              updatedByProfileId = dao.updatedBy,
              updatedAt = dao.updatedAt,
          )
        }
  }

  override suspend fun getUsername(profileId: Int): String? =
      suspendTransaction {
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
      profileId: Int,
      key: String,
      value: String?,
      updatedByProfileId: Int?,
  ) {
    suspendTransaction {
      MemberSettingsDAO.new {
        this.profileId = profileId
        this.settingKey = key
        this.settingValue = value
        this.updatedBy = updatedByProfileId
        this.updatedAt = LocalDateTime.now(utcZone)
      }
    }
    log.info("Appended member setting '$key' for profile $profileId (value=$value)")
  }
}

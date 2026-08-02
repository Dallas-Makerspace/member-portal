package org.dallasmakerspace.members

import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

class MemberSettingsRepositoryMock @Inject constructor() : IMemberSettingsRepository {

  private val profileIds = ConcurrentHashMap<String, Int>()
  private val nextProfileId = AtomicInteger(1)
  private val store = ConcurrentHashMap<Int, MutableList<MemberSettingsRecord>>()

  override suspend fun getProfileId(username: String): Int? =
      profileIds.getOrPut(username) { nextProfileId.getAndIncrement() }

  override suspend fun getLatest(profileId: Int): Map<String, String> {
    val records = store[profileId] ?: return emptyMap()
    return records.asReversed()
        .mapNotNull { record -> record.value?.let { record.key to it } }
        .distinctBy { it.first }
        .toMap()
  }

  override suspend fun getHistory(profileId: Int): List<MemberSettingsRecord> =
      store[profileId]?.asReversed()?.toList() ?: emptyList()

  override suspend fun getUsername(profileId: Int): String? =
      profileIds.entries.firstOrNull { it.value == profileId }?.key

  override suspend fun getUsernames(profileIds: Collection<Int>): Map<Int, String> =
      this.profileIds.entries.filter { (_, id) -> id in profileIds }.associate { (username, id) ->
        id to username
      }

  override suspend fun append(profileId: Int, key: String, value: String?, updatedByProfileId: Int?) {
    store
        .getOrPut(profileId) { mutableListOf() }
        .add(
            MemberSettingsRecord(
                key = key,
                value = value,
                updatedByProfileId = updatedByProfileId,
                updatedAt = LocalDateTime.now(),
            ))
  }
}

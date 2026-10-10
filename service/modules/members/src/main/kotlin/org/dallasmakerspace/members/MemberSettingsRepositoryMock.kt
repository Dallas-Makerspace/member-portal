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

  override suspend fun getLatest(username: String): Map<String, String> {
    val records = store[getProfileId(username)] ?: return emptyMap()
    return records
        .asReversed()
        .mapNotNull { record -> record.value?.let { record.key to it } }
        .distinctBy { it.first }
        .toMap()
  }

  override suspend fun getHistory(username: String): List<MemberSettingsRecord> =
      store[getProfileId(username)]?.asReversed()?.map { record ->
        record.copy(updatedByUsername = record.updatedByProfileId?.let { getUsername(it) })
      } ?: emptyList()

  override suspend fun getUsername(profileId: Int): String? =
      profileIds.entries.firstOrNull { it.value == profileId }?.key

  override suspend fun append(
      profileId: Int,
      key: String,
      value: String?,
      updatedByProfileId: Int?
  ) {
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

package org.dallasmakerspace.members

import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

/**
 * In-memory mock repository for group settings, used when `app.use-mock-services` is enabled.
 * Keeps its own group-name-to-id map rather than delegating to [GroupHistoryRepositoryMock] (whose
 * `getOrCreateGroup` always returns `0`), so that settings for different groups don't collide onto
 * one bucket in tests/dev.
 */
class GroupSettingsRepositoryMock @Inject constructor() : IGroupSettingsRepository {

  private val groupIds = ConcurrentHashMap<String, Int>()
  private val nextGroupId = AtomicInteger(1)
  private val profileIds = ConcurrentHashMap<String, Int>()
  private val nextProfileId = AtomicInteger(1)
  private val store = ConcurrentHashMap<Int, MutableList<GroupSettingsRecord>>()

  override suspend fun getGroupId(name: String): Int? = groupIds[name]

  override suspend fun getOrCreateGroupId(name: String, dn: String?): Int =
      groupIds.getOrPut(name) { nextGroupId.getAndIncrement() }

  override suspend fun getProfileId(username: String): Int? =
      profileIds.getOrPut(username) { nextProfileId.getAndIncrement() }

  override suspend fun getLatest(groupId: Int): Map<String, String> {
    val records = store[groupId] ?: return emptyMap()
    return records
        .asReversed()
        .mapNotNull { record -> record.value?.let { record.key to it } }
        .distinctBy { it.first }
        .toMap()
  }

  override suspend fun getHistory(groupId: Int): List<GroupSettingsRecord> =
      store[groupId]?.asReversed()?.toList() ?: emptyList()

  override suspend fun getUsername(profileId: Int): String? =
      profileIds.entries.firstOrNull { it.value == profileId }?.key

  override suspend fun getUsernames(profileIds: Collection<Int>): Map<Int, String> =
      this.profileIds.entries
          .filter { (_, id) -> id in profileIds }
          .associate { (username, id) -> id to username }

  override suspend fun append(
      groupId: Int,
      key: String,
      value: String?,
      updatedByProfileId: Int?
  ) {
    store
        .getOrPut(groupId) { mutableListOf() }
        .add(
            GroupSettingsRecord(
                key = key,
                value = value,
                updatedByProfileId = updatedByProfileId,
                updatedAt = LocalDateTime.now(),
            ))
  }
}

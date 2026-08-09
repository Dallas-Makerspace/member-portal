package org.dallasmakerspace.members

import java.time.LocalDateTime

/** A single append-only setting change record. */
data class GroupSettingsRecord(
    val key: String,
    val value: String?,
    val updatedByProfileId: Int?,
    val updatedAt: LocalDateTime,
)

interface IGroupSettingsRepository {

  /**
   * Resolves a group name to its group id, or null if no `groups` row exists for it yet. Does not
   * create a row — use [getOrCreateGroupId] on write paths.
   */
  suspend fun getGroupId(name: String): Int?

  /**
   * Resolves a group name to its group id, creating a `groups` row if one does not exist yet (AD
   * groups are only lazily mirrored into the `groups` table). Delegates to
   * [IGroupHistoryRepository.getOrCreateGroup] so both features share one source of truth for
   * `groups` row creation.
   */
  suspend fun getOrCreateGroupId(name: String, dn: String?): Int

  /** Resolves a member username to its profile id, or null if the member does not exist. Used to
   * resolve the actor (the member making the change), not the group itself. */
  suspend fun getProfileId(username: String): Int?

  /**
   * Returns the latest non-null value per setting key for a group. Keys that have never been set,
   * or whose latest record is a clear (NULL value), are omitted.
   */
  suspend fun getLatest(groupId: Int): Map<String, String>

  /**
   * Returns all setting change records for a group in reverse chronological order (newest first).
   * Unlike [getLatest], cleared (NULL value) records are included so the full audit trail is
   * visible.
   */
  suspend fun getHistory(groupId: Int): List<GroupSettingsRecord>

  /** Resolves a profile id to its username, or null if the profile does not exist. */
  suspend fun getUsername(profileId: Int): String?

  /** Resolves a batch of profile ids to usernames. Ids that do not exist are omitted. */
  suspend fun getUsernames(profileIds: Collection<Int>): Map<Int, String>

  /**
   * Appends a new setting record. A NULL value records that the setting was cleared.
   *
   * @param updatedByProfileId The profile id of the member who made the change (may be null if the
   *   change was not performed by a member).
   */
  suspend fun append(groupId: Int, key: String, value: String?, updatedByProfileId: Int?)
}

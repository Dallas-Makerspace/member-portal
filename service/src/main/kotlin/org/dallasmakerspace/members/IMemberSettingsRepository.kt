package org.dallasmakerspace.members

import java.time.LocalDateTime

/** A single append-only setting change record. */
data class MemberSettingsRecord(
    val key: String,
    val value: String?,
    val updatedByProfileId: Int?,
    val updatedAt: LocalDateTime,
)

interface IMemberSettingsRepository {

  /** Resolves a username to its profile id, or null if the member does not exist. */
  suspend fun getProfileId(username: String): Int?

  /**
   * Returns the latest non-null value per setting key for a member. Keys that have never been set,
   * or whose latest record is a clear (NULL value), are omitted.
   */
  suspend fun getLatest(profileId: Int): Map<String, String>

  /**
   * Returns all setting change records for a member in reverse chronological order (newest first).
   * Unlike [getLatest], cleared (NULL value) records are included so the full audit trail is
   * visible.
   */
  suspend fun getHistory(profileId: Int): List<MemberSettingsRecord>

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
  suspend fun append(profileId: Int, key: String, value: String?, updatedByProfileId: Int?)
}

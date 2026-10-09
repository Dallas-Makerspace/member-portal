package org.dallasmakerspace.members

import java.time.LocalDateTime

/** A single append-only setting change record. */
data class MemberSettingsRecord(
    val key: String,
    val value: String?,
    val updatedByProfileId: Int?,
    val updatedAt: LocalDateTime,
    val updatedByUsername: String? = null,
)

interface IMemberSettingsRepository {

  /** Resolves a username to its profile id, or null if the member does not exist. */
  suspend fun getProfileId(username: String): Int?

  /**
   * Returns the latest non-null value per setting key for a member. Keys that have never been set,
   * or whose latest record is a clear (NULL value), are omitted. Empty if the member doesn't exist.
   */
  suspend fun getLatest(username: String): Map<String, String>

  /**
   * Returns all setting change records for a member in reverse chronological order (newest first),
   * with [MemberSettingsRecord.updatedByUsername] resolved. Unlike [getLatest], cleared (NULL
   * value) records are included so the full audit trail is visible. Empty if the member doesn't
   * exist.
   */
  suspend fun getHistory(username: String): List<MemberSettingsRecord>

  /** Resolves a profile id to its username, or null if the profile does not exist. */
  suspend fun getUsername(profileId: Int): String?

  /**
   * Appends a new setting record. A NULL value records that the setting was cleared.
   *
   * @param updatedByProfileId The profile id of the member who made the change (may be null if the
   *   change was not performed by a member).
   */
  suspend fun append(profileId: Int, key: String, value: String?, updatedByProfileId: Int?)
}

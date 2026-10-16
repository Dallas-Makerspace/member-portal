package org.dallasmakerspace.members

import org.dallasmakerspace.models.ArchivedGroup

/** Where an archived group's member-list snapshot sits among its run's step artifacts. */
const val ARCHIVE_ARTIFACT_STEP_INDEX = 1
const val ARCHIVE_ARTIFACT_NAME = "member-list"

/**
 * Archive lifecycle of `groups` rows. Rows are found by name among ACTIVE/DISABLED rows only; the
 * boolean results say whether the post-condition holds, so every call is safe to repeat. The caller
 * makes sure a row exists first (see [IGroupHistoryRepository.getOrCreateGroup]).
 */
interface IGroupArchiveRepository {
  /** ACTIVE to DISABLED. True if the group's row is DISABLED afterwards; false if it has no row. */
  suspend fun markDisabled(groupName: String): Boolean

  /** DISABLED to ACTIVE (rollback). True if the group's row is ACTIVE afterwards. */
  suspend fun markActive(groupName: String): Boolean

  /**
   * ACTIVE or DISABLED to ARCHIVED with who, why and which run; archived_at is now (UTC). A GUID in
   * [objectGuid] is stored only if the row has none. True if the group is archived by [runId]
   * afterwards (also when an earlier call of the same run already did it).
   */
  suspend fun finalizeArchived(
      groupName: String,
      archivedBy: String,
      reason: String,
      runId: Long?,
      objectGuid: String? = null,
  ): Boolean

  /**
   * Sets the GUID of the group's live (ACTIVE/DISABLED) row, e.g. after a rollback recreated the
   * group in AD with a new identity. True if the row holds [objectGuid] afterwards.
   */
  suspend fun updateObjectGuid(groupName: String, objectGuid: String): Boolean

  /** Status of the group's live row, else ARCHIVED if only archived rows exist, else null. */
  suspend fun statusOf(groupName: String): String?

  /**
   * Archived groups, newest first, whose names start with any of [prefixes] (case-insensitive;
   * blank entries ignored; no prefixes means all).
   */
  suspend fun listArchived(prefixes: List<String>): List<ArchivedGroup>
}

/** True if [name] starts with one of [prefixes]; no (non-blank) prefix matches everything. */
internal fun matchesAnyPrefix(name: String, prefixes: List<String>): Boolean {
  val wanted = prefixes.map { it.trim() }.filter { it.isNotEmpty() }
  return wanted.isEmpty() || wanted.any { name.startsWith(it, ignoreCase = true) }
}

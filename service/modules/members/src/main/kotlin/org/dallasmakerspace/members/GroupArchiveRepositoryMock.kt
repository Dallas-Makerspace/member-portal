package org.dallasmakerspace.members

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import org.dallasmakerspace.models.ArchivedGroup

/** In-memory stand-in for mock mode; remembers state only for the life of the process. */
class GroupArchiveRepositoryMock : IGroupArchiveRepository {
  private val statuses = ConcurrentHashMap<String, String>()
  private val archived = ConcurrentHashMap<String, ArchivedGroup>()
  private val guids = ConcurrentHashMap<String, String>()

  private fun key(name: String) = name.lowercase()

  override suspend fun markDisabled(groupName: String): Boolean {
    statuses.compute(key(groupName)) { _, old ->
      if (old == null || old == GROUP_STATUS_ACTIVE) GROUP_STATUS_DISABLED else old
    }
    return statuses[key(groupName)] == GROUP_STATUS_DISABLED
  }

  override suspend fun markActive(groupName: String): Boolean {
    statuses.compute(key(groupName)) { _, old ->
      if (old == GROUP_STATUS_DISABLED) GROUP_STATUS_ACTIVE else old
    }
    return statuses[key(groupName)] == GROUP_STATUS_ACTIVE
  }

  override suspend fun finalizeArchived(
      groupName: String,
      archivedBy: String,
      reason: String,
      runId: Long?,
      objectGuid: String?,
  ): Boolean {
    val k = key(groupName)
    if (statuses[k] == GROUP_STATUS_ARCHIVED) return runId != null && archived[k]?.runId == runId
    statuses[k] = GROUP_STATUS_ARCHIVED
    archived[k] =
        ArchivedGroup(
            id = archived.size + 1,
            name = groupName,
            archivedAt = Instant.now().toString(),
            archivedBy = archivedBy,
            reason = reason,
            runId = runId,
            artifactStepIndex = runId?.let { ARCHIVE_ARTIFACT_STEP_INDEX },
            artifactName = runId?.let { ARCHIVE_ARTIFACT_NAME },
        )
    return true
  }

  override suspend fun updateObjectGuid(groupName: String, objectGuid: String): Boolean {
    val live = statuses[key(groupName)].let { it == GROUP_STATUS_ACTIVE || it == GROUP_STATUS_DISABLED }
    if (live) guids[key(groupName)] = objectGuid.lowercase()
    return live
  }

  override suspend fun objectGuidOf(groupName: String): String? = guids[key(groupName)]

  override suspend fun statusOf(groupName: String): String? = statuses[key(groupName)]

  override suspend fun listArchived(prefixes: List<String>): List<ArchivedGroup> =
      archived.values
          .filter { matchesAnyPrefix(it.name, prefixes) }
          .sortedByDescending { it.archivedAt }
}

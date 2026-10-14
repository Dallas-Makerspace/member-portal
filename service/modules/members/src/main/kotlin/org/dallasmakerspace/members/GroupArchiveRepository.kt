package org.dallasmakerspace.members

import java.time.LocalDateTime
import java.time.ZoneOffset
import org.dallasmakerspace.activedirectory.AdGuid
import org.dallasmakerspace.core.db.suspendTransaction
import org.dallasmakerspace.members.db.GroupsTable
import org.dallasmakerspace.models.ArchivedGroup
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update

private val LIVE_STATUSES = listOf(GROUP_STATUS_ACTIVE, GROUP_STATUS_DISABLED)

private const val UNKNOWN_ARCHIVER = "unknown"

/** Exposed implementation. Timestamps are stored as UTC. */
class GroupArchiveRepository(
    private val now: () -> LocalDateTime = { LocalDateTime.now(ZoneOffset.UTC) },
) : IGroupArchiveRepository {

  override suspend fun markDisabled(groupName: String): Boolean = suspendTransaction {
    GroupsTable.update({ liveRow(groupName, GROUP_STATUS_ACTIVE) }) {
      it[GroupsTable.status] = GROUP_STATUS_DISABLED
    }
    !GroupsTable.select { liveRow(groupName, GROUP_STATUS_DISABLED) }.empty()
  }

  override suspend fun markActive(groupName: String): Boolean = suspendTransaction {
    GroupsTable.update({ liveRow(groupName, GROUP_STATUS_DISABLED) }) {
      it[GroupsTable.status] = GROUP_STATUS_ACTIVE
    }
    !GroupsTable.select { liveRow(groupName, GROUP_STATUS_ACTIVE) }.empty()
  }

  override suspend fun finalizeArchived(
      groupName: String,
      archivedBy: String,
      reason: String,
      runId: Long?,
      objectGuid: String?,
  ): Boolean = suspendTransaction {
    val guid = AdGuid.normalize(objectGuid)
    if (guid != null && GroupsTable.select { GroupsTable.objectGuid eq guid }.empty()) {
      GroupsTable.update({ anyLiveRow(groupName) and GroupsTable.objectGuid.isNull() }) {
        it[GroupsTable.objectGuid] = guid
      }
    }
    val archivedAt = now()
    val changed =
        GroupsTable.update({ anyLiveRow(groupName) }) {
          it[GroupsTable.status] = GROUP_STATUS_ARCHIVED
          it[GroupsTable.archivedAt] = archivedAt
          it[GroupsTable.archivedBy] = archivedBy
          it[GroupsTable.archiveReason] = reason
          it[GroupsTable.archiveRunId] = runId
        }
    changed > 0 || (runId != null && archivedByRun(groupName, runId))
  }

  override suspend fun statusOf(groupName: String): String? = suspendTransaction {
    val statuses =
        GroupsTable.select { GroupsTable.name eq groupName }.map { it[GroupsTable.status] }
    statuses.firstOrNull { !it.equals(GROUP_STATUS_ARCHIVED, ignoreCase = true) }
        ?: statuses.firstOrNull()
  }

  override suspend fun listArchived(prefixes: List<String>): List<ArchivedGroup> =
      suspendTransaction {
        GroupsTable.select { GroupsTable.status eq GROUP_STATUS_ARCHIVED }
            .orderBy(
                GroupsTable.archivedAt to SortOrder.DESC, GroupsTable.idColumn to SortOrder.DESC)
            .filter { matchesAnyPrefix(it[GroupsTable.name], prefixes) }
            .map { it.toArchivedGroup() }
      }

  private fun archivedByRun(groupName: String, runId: Long): Boolean =
      !GroupsTable.select {
            (GroupsTable.name eq groupName) and
                (GroupsTable.status eq GROUP_STATUS_ARCHIVED) and
                (GroupsTable.archiveRunId eq runId)
          }
          .empty()

  private fun liveRow(groupName: String, status: String): Op<Boolean> =
      (GroupsTable.name eq groupName) and (GroupsTable.status eq status)

  private fun anyLiveRow(groupName: String): Op<Boolean> =
      (GroupsTable.name eq groupName) and (GroupsTable.status inList LIVE_STATUSES)

  private fun ResultRow.toArchivedGroup(): ArchivedGroup {
    val runId = this[GroupsTable.archiveRunId]
    val at = this[GroupsTable.archivedAt] ?: this[GroupsTable.created]
    return ArchivedGroup(
        id = this[GroupsTable.idColumn].value,
        name = this[GroupsTable.name],
        archivedAt = at.toInstant(ZoneOffset.UTC).toString(),
        archivedBy = this[GroupsTable.archivedBy] ?: UNKNOWN_ARCHIVER,
        reason = this[GroupsTable.archiveReason].orEmpty(),
        runId = runId,
        artifactStepIndex = runId?.let { ARCHIVE_ARTIFACT_STEP_INDEX },
        artifactName = runId?.let { ARCHIVE_ARTIFACT_NAME },
    )
  }
}

package org.dallasmakerspace.members

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import org.dallasmakerspace.core.db.suspendTransaction
import org.dallasmakerspace.members.db.GroupsTable
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory

data class GuidWriteResult(val updated: Int, val unchanged: Int, val failedIds: List<Int>)

/** Database access for the groups.object_guid backfill job. */
interface IGroupGuidBackfillStore {
  suspend fun listRows(): List<GroupRowRef>

  /** Creates `groups_backup_<yyyyMMdd>` unless it exists; returns its name. */
  suspend fun createBackupTable(date: LocalDate): String

  /** Sets each GUID only where the row's object_guid is still NULL. One transaction per call. */
  suspend fun setGuidsIfNull(assignments: List<Pair<Int, String>>): GuidWriteResult
}

class GroupGuidBackfillStore : IGroupGuidBackfillStore {
  private val logger = LoggerFactory.getLogger(GroupGuidBackfillStore::class.java)

  override suspend fun listRows(): List<GroupRowRef> = suspendTransaction {
    GroupsTable.selectAll().map {
      GroupRowRef(
          it[GroupsTable.idColumn].value,
          it[GroupsTable.name],
          it[GroupsTable.dn],
          it[GroupsTable.objectGuid])
    }
  }

  override suspend fun createBackupTable(date: LocalDate): String {
    val table = "groups_backup_${date.format(DateTimeFormatter.BASIC_ISO_DATE)}"
    suspendTransaction { exec("CREATE TABLE IF NOT EXISTS $table AS SELECT * FROM `groups`") }
    return table
  }

  @Suppress("TooGenericExceptionCaught")
  override suspend fun setGuidsIfNull(assignments: List<Pair<Int, String>>): GuidWriteResult =
      suspendTransaction {
        var updated = 0
        var unchanged = 0
        val failed = mutableListOf<Int>()
        for ((id, guid) in assignments) {
          try {
            val count =
                GroupsTable.update({ (GroupsTable.id eq id) and GroupsTable.objectGuid.isNull() }) {
                  it[objectGuid] = guid
                }
            if (count > 0) updated += count else unchanged++
          } catch (e: ExposedSQLException) {
            logger.warn("GUID update failed for group row {}", id)
            failed.add(id)
          }
        }
        GuidWriteResult(updated, unchanged, failed)
      }
}

class GroupGuidBackfillStoreMock : IGroupGuidBackfillStore {
  override suspend fun listRows(): List<GroupRowRef> = emptyList()

  override suspend fun createBackupTable(date: LocalDate) =
      "groups_backup_${date.format(DateTimeFormatter.BASIC_ISO_DATE)}"

  override suspend fun setGuidsIfNull(assignments: List<Pair<Int, String>>) =
      GuidWriteResult(0, assignments.size, emptyList())
}

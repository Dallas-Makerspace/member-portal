package org.dallasmakerspace.workflows.groups

import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.dallasmakerspace.activedirectory.ADException
import org.dallasmakerspace.activedirectory.ADGroup
import org.dallasmakerspace.activedirectory.ADUser
import org.dallasmakerspace.activedirectory.IActiveDirectoryService
import org.dallasmakerspace.models.DirectoryOus
import org.dallasmakerspace.storage.ObjectKey
import org.dallasmakerspace.storage.ObjectStoreException
import org.dallasmakerspace.storage.ObjectStoreFactory
import org.dallasmakerspace.storage.StorageNamespace
import org.dallasmakerspace.workflows.PermanentStepException
import org.dallasmakerspace.workflows.PublicStep
import org.dallasmakerspace.workflows.Reversibility
import org.dallasmakerspace.workflows.StepCheck
import org.dallasmakerspace.workflows.StepContext
import org.dallasmakerspace.workflows.TransientStepException
import org.dallasmakerspace.workflows.Validation

@Serializable internal data class GroupExportMember(val username: String, val displayName: String)

@Serializable
internal data class GroupExportDocument(
    val schemaVersion: Int,
    val exportedAt: String,
    val groupName: String,
    val groupObjectGuid: String?,
    val memberCount: Int,
    val members: List<GroupExportMember>,
)

/**
 * Read-only: snapshots a group's members to the object store. Export and upload are one step so
 * member data never lands in run state; the persisted output is only the key, count and checksum.
 * The snapshot is kept on rollback.
 */
@Singleton
class ExportGroupMembersStep
@Inject
constructor(
    private val activeDirectory: IActiveDirectoryService,
    objectStoreFactory: ObjectStoreFactory,
) : PublicStep<ExportGroupMembersStep.Args, ExportGroupMembersStep.Result> {

  data class Args(val groupName: String)

  /** [key] is relative to [StorageNamespace.WORKFLOW_EXPORTS]. */
  @Serializable data class Result(val key: String, val memberCount: Int, val sha256: String)

  private val store = objectStoreFactory.forNamespace(StorageNamespace.WORKFLOW_EXPORTS)

  override val id = "group.export-members"
  override val displayName = "Export group members"
  override val reversibility = Reversibility.REVERSIBLE
  override val outputSerializer = Result.serializer()

  override suspend fun check(ctx: StepContext, args: Args): StepCheck<Result> {
    val key = objectKey(ctx.runId, args.groupName)
    val head = storage { store.head(key) } ?: return StepCheck.WillRun("Export the group members")
    val expectedSha = head.metadata[META_SHA256]
    val count = head.metadata[META_MEMBER_COUNT]?.toIntOrNull()
    if (expectedSha == null || count == null) {
      return StepCheck.WillRun("Replace an incomplete export")
    }
    val content = storage { store.get(key) } ?: return StepCheck.WillRun("Export the group members")
    if (sha256Hex(content.bytes) != expectedSha) {
      return StepCheck.WillRun("Replace an export whose checksum does not match")
    }
    return StepCheck.NoOp("Members were already exported", Result(key.value, count, expectedSha))
  }

  override suspend fun validate(ctx: StepContext, args: Args): Validation {
    if (args.groupName.isBlank()) return Validation.Blocked(listOf("A group name is required"))
    val group =
        fetchGroup(args.groupName)
            ?: return Validation.Blocked(listOf("Group '${args.groupName}' was not found"))
    if (!DirectoryOus.isInGroupOu(group.distinguishedName)) {
      return Validation.Blocked(
          listOf("Group '${args.groupName}' is outside the managed group locations"))
    }
    if (group.membersListIncomplete) {
      return Validation.Warnings(
          listOf("The member list is too large to read fully; the export will be incomplete"))
    }
    return Validation.Ok
  }

  override suspend fun run(ctx: StepContext, args: Args): Result {
    val group =
        fetchGroup(args.groupName)
            ?: throw PermanentStepException("Group '${args.groupName}' was not found")
    if (!DirectoryOus.isInGroupOu(group.distinguishedName)) {
      throw PermanentStepException(
          "Group '${args.groupName}' is outside the managed group locations")
    }
    val members =
        group.members
            .distinctBy { it.sAMAccountName.lowercase() }
            .sortedBy { it.sAMAccountName.lowercase() }
            .map { GroupExportMember(it.sAMAccountName, it.exportName()) }
    val document =
        GroupExportDocument(
            schemaVersion = SCHEMA_VERSION,
            exportedAt = ctx.clock.instant().toString(),
            groupName = group.cn,
            groupObjectGuid = group.objectGuid,
            memberCount = members.size,
            members = members,
        )
    val bytes = json.encodeToString(GroupExportDocument.serializer(), document).toByteArray()
    val sha = sha256Hex(bytes)
    val key = objectKey(ctx.runId, args.groupName)
    ctx.checkCancelled()
    storage {
      store.put(
          key,
          bytes,
          "application/json",
          mapOf(META_SHA256 to sha, META_MEMBER_COUNT to members.size.toString()))
    }
    return Result(key.value, members.size, sha)
  }

  /** Keeps the snapshot: it may be the only copy of the member list. */
  override suspend fun rollback(ctx: StepContext, args: Args, output: Result) = Unit

  override fun summarize(output: Result) =
      if (output.memberCount == 1) "1 member exported" else "${output.memberCount} members exported"

  @Suppress("TooGenericExceptionCaught")
  private suspend fun fetchGroup(name: String): ADGroup? =
      try {
        withContext(Dispatchers.IO) { activeDirectory.getGroup(name.trim()) }
      } catch (e: CancellationException) {
        throw e
      } catch (e: ADException) {
        if (e.message?.contains("not found", ignoreCase = true) == true) {
          null
        } else {
          throw TransientStepException("Could not read the group from the directory", e)
        }
      } catch (e: Exception) {
        throw TransientStepException("Could not read the group from the directory", e)
      }

  private suspend fun <T> storage(block: suspend () -> T): T =
      try {
        block()
      } catch (e: ObjectStoreException) {
        throw TransientStepException("Object store request failed: ${e.message}", e)
      }

  private fun ADUser.exportName(): String =
      displayName?.takeIf { it.isNotBlank() }
          ?: listOfNotNull(givenName, sn)
              .filter { it.isNotBlank() }
              .joinToString(" ")
              .ifBlank { sAMAccountName }

  companion object {
    const val SCHEMA_VERSION = 1
    private const val META_SHA256 = "sha256"
    private const val META_MEMBER_COUNT = "member-count"
    private const val MAX_SLUG_LENGTH = 60
    private const val HEX_MASK = 0xff

    private val json = Json { encodeDefaults = true }
    private val nonSlugChars = Regex("[^a-z0-9]+")

    /** Derived from the run id, so a retry or crash-resume recomputes the same key. */
    fun objectKey(runId: Long, groupName: String): ObjectKey =
        ObjectKey("group-exports/$runId/${slug(groupName)}.json")

    /** Lowercase letters, digits and single dashes only, so the raw name never reaches a key. */
    fun slug(groupName: String): String =
        groupName
            .lowercase()
            .replace(nonSlugChars, "-")
            .trim('-')
            .take(MAX_SLUG_LENGTH)
            .trim('-')
            .ifEmpty { "group" }

    internal fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
          "%02x".format(it.toInt() and HEX_MASK)
        }
  }
}

package org.dallasmakerspace.workflows.groups

import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.dallasmakerspace.activedirectory.ADGroup
import org.dallasmakerspace.activedirectory.ADUser
import org.dallasmakerspace.activedirectory.GroupNotFoundException
import org.dallasmakerspace.activedirectory.IActiveDirectoryService
import org.dallasmakerspace.models.DirectoryOus
import org.dallasmakerspace.storage.ObjectKey
import org.dallasmakerspace.storage.ObjectStoreException
import org.dallasmakerspace.storage.ObjectStoreFactory
import org.dallasmakerspace.storage.StorageNamespace
import org.dallasmakerspace.storage.StoredObject
import org.dallasmakerspace.workflows.ArtifactRef
import org.dallasmakerspace.workflows.PermanentStepException
import org.dallasmakerspace.workflows.PublicStep
import org.dallasmakerspace.workflows.Reversibility
import org.dallasmakerspace.workflows.StepCheck
import org.dallasmakerspace.workflows.StepContext
import org.dallasmakerspace.workflows.StepMode
import org.dallasmakerspace.workflows.TransientStepException
import org.dallasmakerspace.workflows.Validation
import software.amazon.awssdk.awscore.exception.AwsServiceException

@Serializable internal data class GroupExportMember(val username: String, val displayName: String)

@Serializable
internal data class GroupExportDocument(
    val schemaVersion: Int,
    val exportedAt: String,
    val groupName: String,
    val groupObjectGuid: String?,
    val memberCount: Int,
    val members: List<GroupExportMember>,
    /** Raw member attribute count the directory reported. */
    val sourceMemberCount: Int,
    /** User-looking member entries that did not resolve to a user. */
    val unresolvedMemberCount: Int,
    /** Nested groups, listed but not expanded. */
    val nestedGroups: List<String>,
    /** False if the directory truncated the list or any member did not resolve. */
    val complete: Boolean,
)

/**
 * Read-only: snapshots a group's members to the object store. Export and upload are one step so
 * member data never lands in run state; the persisted output is only the key, counts and checksum.
 * The snapshot is kept on rollback.
 */
@Singleton
class ExportGroupMembersStep
internal constructor(
    private val activeDirectory: IActiveDirectoryService,
    objectStoreFactory: ObjectStoreFactory,
    private val directoryDispatcher: CoroutineDispatcher,
) : PublicStep<ExportGroupMembersStep.Args, ExportGroupMembersStep.Result> {

  @Inject
  constructor(
      activeDirectory: IActiveDirectoryService,
      objectStoreFactory: ObjectStoreFactory,
  ) : this(activeDirectory, objectStoreFactory, Dispatchers.IO)

  /**
   * With [requireComplete], an incomplete export fails the step instead of being uploaded.
   * [downloadInfraOnly] limits the download of the exported file to infrastructure admins.
   */
  data class Args(
      val groupName: String,
      val requireComplete: Boolean = false,
      val downloadInfraOnly: Boolean = false,
  )

  /** [key] is relative to [StorageNamespace.WORKFLOW_EXPORTS]. Never holds member data. */
  @Serializable
  data class Result(
      val key: String,
      val memberCount: Int,
      val sha256: String,
      val complete: Boolean,
      val sourceMemberCount: Int,
      val nestedGroupCount: Int,
  )

  private val store = objectStoreFactory.forNamespace(StorageNamespace.WORKFLOW_EXPORTS)

  override val id = "group.export-members"
  override val displayName = "Export group members"
  override val reversibility = Reversibility.REVERSIBLE
  override val outputSerializer = Result.serializer()

  override suspend fun check(ctx: StepContext, args: Args): StepCheck<Result> {
    if (ctx.mode == StepMode.PREFLIGHT) return StepCheck.WillRun("Export the group members")
    val key = objectKey(ctx.runId, args.groupName)
    val head = storage { store.head(key) } ?: return StepCheck.WillRun("Export the group members")
    val stored = storedResult(key, head) ?: return StepCheck.WillRun("Replace an incomplete export")
    if (args.requireComplete && !stored.complete) {
      return StepCheck.WillRun("Replace an export that is not complete")
    }
    return StepCheck.NoOp("Members were already exported", stored)
  }

  override suspend fun validate(ctx: StepContext, args: Args): Validation {
    if (args.groupName.isBlank()) return Validation.Blocked(listOf("A group name is required"))
    if (ctx.mode != StepMode.PREFLIGHT) return Validation.Ok
    val group =
        fetchGroup(args.groupName)
            ?: return Validation.Blocked(listOf("Group '${args.groupName}' was not found"))
    if (!DirectoryOus.isInGroupOu(group.distinguishedName)) {
      return Validation.Blocked(
          listOf("Group '${args.groupName}' is outside the managed group locations"))
    }
    val problems = snapshot(group).problems()
    return when {
      problems.isEmpty() -> Validation.Ok
      args.requireComplete -> Validation.Blocked(problems)
      else -> Validation.Warnings(problems)
    }
  }

  override suspend fun run(ctx: StepContext, args: Args): Result {
    val group =
        fetchGroup(args.groupName)
            ?: throw PermanentStepException("Group '${args.groupName}' was not found")
    if (!DirectoryOus.isInGroupOu(group.distinguishedName)) {
      throw PermanentStepException(
          "Group '${args.groupName}' is outside the managed group locations")
    }
    val snap = snapshot(group)
    if (args.requireComplete && !snap.complete) {
      throw PermanentStepException(snap.problems().joinToString("; "))
    }
    val document =
        GroupExportDocument(
            schemaVersion = SCHEMA_VERSION,
            exportedAt = ctx.clock.instant().toString(),
            groupName = group.cn,
            groupObjectGuid = group.objectGuid,
            memberCount = snap.members.size,
            members = snap.members,
            sourceMemberCount = snap.sourceMemberCount,
            unresolvedMemberCount = snap.unresolved,
            nestedGroups = snap.nestedGroups,
            complete = snap.complete,
        )
    val bytes = json.encodeToString(GroupExportDocument.serializer(), document).toByteArray()
    val result =
        Result(
            key = objectKey(ctx.runId, args.groupName).value,
            memberCount = snap.members.size,
            sha256 = sha256Hex(bytes),
            complete = snap.complete,
            sourceMemberCount = snap.sourceMemberCount,
            nestedGroupCount = snap.nestedGroups.size,
        )
    ctx.checkCancelled()
    storage {
      store.put(ObjectKey(result.key), bytes, "application/json", metadataOf(result, bytes.size))
    }
    return result
  }

  /** Keeps the snapshot: it may be the only copy of the member list. */
  override suspend fun rollback(ctx: StepContext, args: Args, output: Result) = Unit

  override fun artifacts(args: Args, output: Result) =
      listOf(
          ArtifactRef(
              name = MEMBER_LIST_ARTIFACT,
              label = "Member list (JSON)",
              contentType = "application/json",
              namespace = StorageNamespace.WORKFLOW_EXPORTS.name,
              key = output.key,
              infraOnly = args.downloadInfraOnly,
          ))

  override fun summarize(output: Result): String {
    val noun = if (output.memberCount == 1) "member" else "members"
    return "${output.memberCount} $noun exported" + if (output.complete) "" else " (incomplete)"
  }

  @Suppress("TooGenericExceptionCaught")
  private suspend fun fetchGroup(name: String): ADGroup? =
      try {
        withContext(directoryDispatcher) { activeDirectory.getGroup(name.trim()) }
      } catch (e: CancellationException) {
        throw e
      } catch (e: GroupNotFoundException) {
        null
      } catch (e: Exception) {
        throw TransientStepException("Could not read the group from the directory", e)
      }

  private suspend fun <T> storage(block: suspend () -> T): T =
      try {
        block()
      } catch (e: ObjectStoreException) {
        val message = "Object store request failed: ${e.message}"
        throw if (isPermanent(e)) {
          PermanentStepException(message, e)
        } else {
          TransientStepException(message, e)
        }
      }

  /** Rejected by the store (a 4xx other than throttling): retrying cannot help. */
  private fun isPermanent(e: ObjectStoreException) =
      !e.isRetryable &&
          (e.isNotFound || (e.cause as? AwsServiceException)?.statusCode() in CLIENT_ERRORS)

  private class Snapshot(
      val members: List<GroupExportMember>,
      val nestedGroups: List<String>,
      val sourceMemberCount: Int,
      val unresolved: Int,
      val truncated: Boolean,
      val complete: Boolean,
  ) {
    fun problems(): List<String> = buildList {
      if (truncated) add("The member list is too large to read fully; the export is incomplete")
      if (unresolved > 0) add("$unresolved members could not be resolved and are missing")
      if (!truncated && unresolved == 0 && !complete) {
        add("The member counts do not add up; the export is incomplete")
      }
    }
  }

  private fun snapshot(group: ADGroup): Snapshot {
    val members =
        group.members
            .distinctBy { it.sAMAccountName.lowercase() }
            .sortedBy { it.sAMAccountName.lowercase() }
            .map { GroupExportMember(it.sAMAccountName, it.exportName()) }
    val accounted = group.members.size + group.nestedGroups.size + group.unresolvedMemberCount
    val source = group.sourceMemberCount ?: accounted
    val truncated = group.membersListIncomplete
    val complete = !truncated && group.unresolvedMemberCount == 0 && source == accounted
    return Snapshot(
        members, group.nestedGroups, source, group.unresolvedMemberCount, truncated, complete)
  }

  private fun metadataOf(result: Result, size: Int) =
      mapOf(
          META_SHA256 to result.sha256,
          META_MEMBER_COUNT to result.memberCount.toString(),
          META_SOURCE_COUNT to result.sourceMemberCount.toString(),
          META_NESTED_COUNT to result.nestedGroupCount.toString(),
          META_COMPLETE to result.complete.toString(),
          META_SIZE to size.toString(),
      )

  /** The earlier upload's result, from its metadata alone; null if absent or inconsistent. */
  private fun storedResult(key: ObjectKey, head: StoredObject): Result? {
    val m = head.metadata
    val size = m[META_SIZE]?.toLongOrNull()
    if (size == null || size != head.sizeBytes) return null
    return Result(
        key = key.value,
        memberCount = m[META_MEMBER_COUNT]?.toIntOrNull() ?: return null,
        sha256 = m[META_SHA256]?.takeIf { it.isNotBlank() } ?: return null,
        complete = m[META_COMPLETE]?.toBooleanStrictOrNull() ?: return null,
        sourceMemberCount = m[META_SOURCE_COUNT]?.toIntOrNull() ?: return null,
        nestedGroupCount = m[META_NESTED_COUNT]?.toIntOrNull() ?: return null,
    )
  }

  private fun ADUser.exportName(): String =
      displayName.usable()
          ?: listOfNotNull(givenName.usable(), sn.usable()).joinToString(" ").ifBlank {
            sAMAccountName
          }

  /** Absent AD attributes can arrive as the text "null". */
  private fun String?.usable() = this?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

  companion object {
    const val SCHEMA_VERSION = 1
    const val MEMBER_LIST_ARTIFACT = "member-list"
    private const val META_SHA256 = "sha256"
    private const val META_MEMBER_COUNT = "member-count"
    private const val META_SOURCE_COUNT = "source-member-count"
    private const val META_NESTED_COUNT = "nested-group-count"
    private const val META_COMPLETE = "complete"
    private const val META_SIZE = "size-bytes"
    private const val MAX_SLUG_LENGTH = 60
    private const val HEX_MASK = 0xff
    private val CLIENT_ERRORS = 400..499

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

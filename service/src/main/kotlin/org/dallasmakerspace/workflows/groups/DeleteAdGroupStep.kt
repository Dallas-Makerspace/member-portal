package org.dallasmakerspace.workflows.groups

import com.unboundid.ldap.sdk.DN
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.dallasmakerspace.activedirectory.ADGroup
import org.dallasmakerspace.activedirectory.AdGroupSpec
import org.dallasmakerspace.activedirectory.AdGuid
import org.dallasmakerspace.activedirectory.AmbiguousGroupException
import org.dallasmakerspace.activedirectory.GroupCreateRefusedException
import org.dallasmakerspace.activedirectory.GroupDeletionRefusedException
import org.dallasmakerspace.activedirectory.GroupGuidNotFoundException
import org.dallasmakerspace.activedirectory.GroupMembershipRefusedException
import org.dallasmakerspace.activedirectory.GroupNotFoundException
import org.dallasmakerspace.activedirectory.IActiveDirectoryService
import org.dallasmakerspace.members.IGroupArchiveRepository
import org.dallasmakerspace.models.DirectoryOus
import org.dallasmakerspace.models.ProtectedGroups
import org.dallasmakerspace.storage.ObjectKey
import org.dallasmakerspace.storage.ObjectStoreException
import org.dallasmakerspace.storage.ObjectStoreFactory
import org.dallasmakerspace.storage.StorageNamespace
import org.dallasmakerspace.workflows.PermanentStepException
import org.dallasmakerspace.workflows.PublicStep
import org.dallasmakerspace.workflows.Reversibility
import org.dallasmakerspace.workflows.StepCheck
import org.dallasmakerspace.workflows.StepContext
import org.dallasmakerspace.workflows.StepMode
import org.dallasmakerspace.workflows.TransientStepException
import org.dallasmakerspace.workflows.Validation
import org.dallasmakerspace.workflows.groups.references.GroupReferenceChecker
import org.dallasmakerspace.workflows.groups.references.GroupReferenceKinds
import org.slf4j.LoggerFactory

/**
 * Deletes the exact group that was snapshotted (matched by objectGUID and DN, never by name alone)
 * from the main group OU, but only while its membership still matches the snapshot and nothing
 * protects or references it. Best-effort rollback: the group is recreated from the snapshot
 * document (a new identity), its users are re-added, its memberships in main-OU parent groups are
 * restored, and the portal row gets the new GUID. Memberships in other parents are not restored.
 */
@Singleton
class DeleteAdGroupStep
internal constructor(
    private val activeDirectory: IActiveDirectoryService,
    objectStoreFactory: ObjectStoreFactory,
    private val archive: IGroupArchiveRepository,
    private val references: GroupReferenceChecker,
    private val directoryDispatcher: CoroutineDispatcher,
) : PublicStep<DeleteAdGroupStep.Args, DeleteAdGroupStep.Result> {

  @Inject
  constructor(
      activeDirectory: IActiveDirectoryService,
      objectStoreFactory: ObjectStoreFactory,
      archive: IGroupArchiveRepository,
      references: GroupReferenceChecker,
  ) : this(activeDirectory, objectStoreFactory, archive, references, Dispatchers.IO)

  /** [snapshot] is the export step's result; null while planning, before that step has run. */
  data class Args(val groupName: String, val snapshot: ExportGroupMembersStep.Result?)

  /** The parent counts are copied from the snapshot so the summary can say what a rollback loses. */
  @Serializable
  data class Result(
      val groupName: String,
      val objectGuid: String?,
      val deleted: Boolean,
      val parentGroupCount: Int = 0,
      val parentGroupsOutsideMainCount: Int = 0,
  )

  private sealed interface Lookup {
    data class Found(val group: ADGroup) : Lookup

    data object Absent : Lookup

    data class Ambiguous(val count: Int) : Lookup
  }

  private val store = objectStoreFactory.forNamespace(StorageNamespace.WORKFLOW_EXPORTS)
  private val log = LoggerFactory.getLogger(DeleteAdGroupStep::class.java)

  override val id = "group.delete-ad-group"
  override val displayName = "Delete directory group"
  override val reversibility = Reversibility.BEST_EFFORT
  override val outputSerializer = Result.serializer()

  override suspend fun check(ctx: StepContext, args: Args): StepCheck<Result> {
    val name = args.groupName.trim()
    if (lookup(name) != Lookup.Absent) {
      return StepCheck.WillRun("Delete the group from the directory")
    }
    return StepCheck.NoOp(
        "The group is already absent from the directory", resultOf(name, args, deleted = false))
  }

  override suspend fun validate(ctx: StepContext, args: Args): Validation {
    val name = args.groupName.trim()
    if (name.isEmpty()) return Validation.Blocked(listOf("A group name is required"))
    val group =
        when (val found = lookup(name)) {
          Lookup.Absent -> return Validation.Ok
          is Lookup.Ambiguous ->
              return Validation.Blocked(
                  listOf("${found.count} groups named '$name' exist; resolve the duplicate first"))
          is Lookup.Found -> found.group
        }
    val problems = problemsWith(ctx, name, group, args.snapshot)
    return if (problems.isEmpty()) Validation.Ok else Validation.Blocked(problems)
  }

  override suspend fun run(ctx: StepContext, args: Args): Result {
    val name = args.groupName.trim()
    val guid =
        args.snapshot?.groupObjectGuid?.takeIf { it.isNotBlank() }
            ?: throw PermanentStepException("The snapshot has no group identity to delete by")
    val deleted =
        try {
          withContext(directoryDispatcher) { activeDirectory.deleteGroup(name, guid) }
        } catch (e: GroupDeletionRefusedException) {
          throw PermanentStepException("Group '$name' was not deleted: ${e.reason}", e)
        } catch (e: GroupGuidNotFoundException) {
          throw PermanentStepException(
              "No group with the snapshotted identity exists; nothing was deleted", e)
        } catch (e: AmbiguousGroupException) {
          throw PermanentStepException("Group '$name' was not deleted: the name is duplicated", e)
        }
    return resultOf(name, args, deleted)
  }

  override suspend fun rollback(ctx: StepContext, args: Args, output: Result) {
    val name = args.groupName.trim()
    val snapshot =
        args.snapshot ?: throw PermanentStepException("The member snapshot is not available")
    val doc = readDocument(snapshot)
    val usernames = doc.members.map { it.username }
    if (ExportGroupMembersStep.membersDigest(usernames) != snapshot.membersSha256) {
      throw PermanentStepException("The stored snapshot does not match the recorded checksum")
    }
    val created = recreate(name, doc)
    val missing = missingMembers(name, usernames)
    missing.chunked(ADD_BATCH).forEach { batch ->
      ctx.checkCancelled()
      withContext(directoryDispatcher) { activeDirectory.addUsersToGroup(batch, name) }
    }
    verifyMembers(name, usernames)
    restoreParents(ctx, doc.groupName.ifBlank { name }, doc.parentGroups)
    created.objectGuid?.let {
      if (!archive.updateObjectGuid(name, it)) {
        throw TransientStepException("Could not update the portal record with the new identity")
      }
    } ?: log.warn("Recreated group has no GUID; the portal record keeps the old one")
  }

  override fun summarize(output: Result): String {
    val base =
        if (output.deleted) "Group deleted from the directory" else "Group was already absent"
    if (output.parentGroupCount == 0) return base
    val unrestorable = output.parentGroupsOutsideMainCount
    return "$base; it was a member of ${output.parentGroupCount} other group(s)" +
        if (unrestorable > 0) ", $unrestorable of which a rollback cannot restore" else ""
  }

  private fun resultOf(name: String, args: Args, deleted: Boolean) =
      Result(
          name,
          args.snapshot?.groupObjectGuid,
          deleted,
          args.snapshot?.parentGroupCount ?: 0,
          args.snapshot?.parentGroupsOutsideMainCount ?: 0)

  /** Why this group may not be deleted right now; empty when it may. */
  private suspend fun problemsWith(
      ctx: StepContext,
      name: String,
      group: ADGroup,
      snapshot: ExportGroupMembersStep.Result?,
  ): List<String> {
    if (!DirectoryOus.isInMainGroupOu(group.distinguishedName)) {
      return listOf("Group '$name' is outside the main group location and cannot be deleted")
    }
    val problems = mutableListOf<String>()
    val protected = ProtectedGroups.find(group.cn) ?: ProtectedGroups.find(name)
    if (protected != null) problems += "Protected group: ${protected.reason}"
    when {
      snapshot != null -> problems += snapshotProblems(group, snapshot)
      ctx.mode != StepMode.PREFLIGHT -> problems += "The member snapshot is not available"
    }
    if (ctx.mode != StepMode.PREFLIGHT) {
      references
          .check(name)
          .blocking
          .filter { protected == null || it.kind != GroupReferenceKinds.PROTECTED }
          .forEach { problems += "${it.system}: ${it.detail}" }
    }
    return problems
  }

  private fun snapshotProblems(group: ADGroup, snapshot: ExportGroupMembersStep.Result) =
      buildList {
        if (!sameGuid(group.objectGuid, snapshot.groupObjectGuid) ||
            !DirectoryOus.sameDn(group.distinguishedName, snapshot.groupDn)) {
          add("This is not the group that was snapshotted: its identity or location differs")
        }
        if (group.membersListIncomplete) add("The member list is too large to compare fully")
        val digest = ExportGroupMembersStep.membersDigest(group.members.map { it.sAMAccountName })
        if (digest != snapshot.membersSha256) {
          add("The members changed since the snapshot was taken; run the archive again")
        }
        if (group.nestedGroups.isNotEmpty() || snapshot.nestedGroupCount != 0) {
          add("The group has nested groups, which an archive cannot restore")
        }
        if (group.unresolvedMemberCount != snapshot.unresolvedMemberCount) {
          add("The number of unresolved members changed since the snapshot was taken")
        }
      }

  private fun sameGuid(a: String?, b: String?): Boolean {
    if (a.isNullOrBlank() || b.isNullOrBlank()) return false
    fun canonical(guid: String) = AdGuid.normalize(guid) ?: guid.trim().lowercase()
    return canonical(a) == canonical(b)
  }

  private suspend fun recreate(name: String, doc: GroupExportDocument) =
      try {
        val parent =
            runCatching { DN(doc.groupDn).parent?.toString() }
                .getOrNull()
                ?.takeIf { DirectoryOus.isInMainGroupOu(doc.groupDn) }
                ?: throw PermanentStepException(
                    "The recorded location of the group is not in the main group location")
        val spec =
            AdGroupSpec(
                doc.groupName.ifBlank { name },
                parent,
                doc.description,
                doc.managedBy,
                doc.samAccountName,
                doc.groupType)
        withContext(directoryDispatcher) { activeDirectory.createGroup(spec) }
      } catch (e: GroupCreateRefusedException) {
        throw PermanentStepException(e.message ?: "The group cannot be recreated", e)
      }

  /** Adds the group back to the parents inside the main OU; the rest are only counted. */
  private suspend fun restoreParents(ctx: StepContext, name: String, parents: List<String>) {
    var notRestored = 0
    parents.forEach { parentDn ->
      ctx.checkCancelled()
      val restored =
          DirectoryOus.isInMainGroupOu(parentDn) &&
              try {
                withContext(directoryDispatcher) {
                  activeDirectory.addGroupToParentGroup(name, parentDn)
                }
                true
              } catch (e: GroupNotFoundException) {
                false
              } catch (e: GroupMembershipRefusedException) {
                false
              }
      if (!restored) notRestored++
    }
    if (notRestored > 0) {
      log.warn(
          "Group '{}' was a member of {} groups that could not be restored", name, notRestored)
    }
  }

  /** Usernames from the snapshot that the recreated group does not hold yet. */
  private suspend fun missingMembers(name: String, usernames: List<String>): List<String> {
    val current = currentUsernames(name)
    return usernames.filter { it.lowercase() !in current }
  }

  private suspend fun currentUsernames(name: String): Set<String> =
      withContext(directoryDispatcher) { activeDirectory.getGroup(name) }
          .members
          .map { it.sAMAccountName.lowercase() }
          .toSet()

  /** Members that no longer exist in the directory are skipped; any other gap fails the step. */
  private suspend fun verifyMembers(name: String, usernames: List<String>) {
    val still = missingMembers(name, usernames)
    if (still.isEmpty()) return
    val found =
        withContext(directoryDispatcher) { activeDirectory.getMembersByUsernameList(still) }
    val restorable = still.filter { found[it] != null }
    if (restorable.isNotEmpty()) {
      throw TransientStepException("${restorable.size} members could not be re-added")
    }
    log.warn("{} members no longer exist in the directory and were not re-added", still.size)
  }

  private suspend fun readDocument(snapshot: ExportGroupMembersStep.Result): GroupExportDocument {
    val content =
        try {
          store.get(ObjectKey(snapshot.key))
        } catch (e: ObjectStoreException) {
          throw TransientStepException("Object store request failed: ${e.message}", e)
        } ?: throw PermanentStepException("The member snapshot no longer exists in the store")
    return try {
      readJson.decodeFromString(GroupExportDocument.serializer(), content.bytes.decodeToString())
    } catch (e: SerializationException) {
      throw PermanentStepException("The member snapshot could not be read", e)
    }
  }

  @Suppress("TooGenericExceptionCaught")
  private suspend fun lookup(name: String): Lookup =
      try {
        Lookup.Found(withContext(directoryDispatcher) { activeDirectory.getGroupStrict(name) })
      } catch (e: CancellationException) {
        throw e
      } catch (e: GroupNotFoundException) {
        Lookup.Absent
      } catch (e: AmbiguousGroupException) {
        Lookup.Ambiguous(e.count)
      } catch (e: Exception) {
        throw TransientStepException("Could not read the group from the directory", e)
      }

  private companion object {
    const val ADD_BATCH = 50
    val readJson = Json { ignoreUnknownKeys = true }
  }
}

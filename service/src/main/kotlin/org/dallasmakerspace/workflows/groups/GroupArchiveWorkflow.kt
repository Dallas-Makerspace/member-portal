package org.dallasmakerspace.workflows.groups

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import org.dallasmakerspace.activedirectory.ADGroup
import org.dallasmakerspace.activedirectory.AmbiguousGroupException
import org.dallasmakerspace.activedirectory.GroupNotFoundException
import org.dallasmakerspace.activedirectory.IActiveDirectoryService
import org.dallasmakerspace.members.GROUP_STATUS_ARCHIVED
import org.dallasmakerspace.members.IGroupArchiveRepository
import org.dallasmakerspace.models.DirectoryOus
import org.dallasmakerspace.models.ProtectedGroups
import org.dallasmakerspace.models.workflows.WorkflowInputField
import org.dallasmakerspace.workflows.Backoff
import org.dallasmakerspace.workflows.FailurePolicy
import org.dallasmakerspace.workflows.PermanentStepException
import org.dallasmakerspace.workflows.RetryPolicy
import org.dallasmakerspace.workflows.Reversibility
import org.dallasmakerspace.workflows.Step
import org.dallasmakerspace.workflows.StepCheck
import org.dallasmakerspace.workflows.StepContext
import org.dallasmakerspace.workflows.StepMode
import org.dallasmakerspace.workflows.Subject
import org.dallasmakerspace.workflows.TransientStepException
import org.dallasmakerspace.workflows.Validation
import org.dallasmakerspace.workflows.Workflow
import org.dallasmakerspace.workflows.WorkflowAuthorization
import org.dallasmakerspace.workflows.groups.references.GroupReferenceChecker
import org.dallasmakerspace.workflows.groups.references.GroupReferenceKinds

@Serializable data class GroupArchiveInput(val groupName: String, val reason: String)

/**
 * Saves the member list, deletes the directory group and records it as archived. Only the group's
 * administrators (and Domain Admins / Infrastructure members) may start it; the per-group check is
 * the [WorkflowAuthorization.GROUP_ADMIN] authorizer, so no global permission is named here.
 */
@Singleton
class GroupArchiveWorkflow
internal constructor(
    private val exportMembers: ExportGroupMembersStep,
    private val markDisabled: MarkGroupDisabledStep,
    private val deleteAdGroup: DeleteAdGroupStep,
    private val checkArchivable: CheckGroupArchivableStep,
    private val finalizeArchive: FinalizeGroupArchiveStep,
) :
    Workflow<GroupArchiveInput>(
        id = ID,
        version = 1,
        displayName = "Archive group",
        description =
            "Saves the group's member list, deletes the group from the directory and records " +
                "it as archived with who archived it and why.",
        permission = null,
        inputSerializer = GroupArchiveInput.serializer(),
        inputFields =
            listOf(
                WorkflowInputField("groupName", "Group name"),
                WorkflowInputField(
                    name = "reason",
                    label = "Reason",
                    multiline = true,
                    minLength = MIN_REASON,
                    maxLength = MAX_REASON,
                    hint =
                        "Visible to all members in the Archived Groups report. " +
                            "Do not include personal details."),
            ),
        onFailure = FailurePolicy.PAUSE,
        retry =
            RetryPolicy(
                maxAttempts = 3,
                backoff = Backoff.Exponential(initial = 5.seconds, max = 2.minutes)),
        authorization = WorkflowAuthorization.GROUP_ADMIN,
        confirmTypingField = "groupName",
    ) {

  @Inject
  constructor(
      exportMembers: ExportGroupMembersStep,
      markDisabled: MarkGroupDisabledStep,
      deleteAdGroup: DeleteAdGroupStep,
      activeDirectory: IActiveDirectoryService,
      references: GroupReferenceChecker,
      archive: IGroupArchiveRepository,
  ) : this(
      exportMembers,
      markDisabled,
      deleteAdGroup,
      CheckGroupArchivableStep(activeDirectory, references, Dispatchers.IO),
      FinalizeGroupArchiveStep(archive),
  )

  override val notes =
      listOf(
          "The member list is saved to the object store and kept after the archive; only " +
              "Infrastructure members can download it.",
          "The group is deleted from Active Directory. Members lose any access the group grants.",
          "If a run is rolled back after the deletion, the group is recreated with a new " +
              "identity and its members are re-added. Memberships of this group in other " +
              "groups cannot be restored.",
      )

  override fun subjects(input: GroupArchiveInput) =
      listOf(Subject("group", input.groupName.trim()))

  override fun steps() =
      listOf(
          step(checkArchivable) { CheckGroupArchivableStep.Args(input.groupName.trim()) },
          step(exportMembers) {
            ExportGroupMembersStep.Args(
                input.groupName.trim(),
                requireComplete = true,
                downloadInfraOnly = true,
                keyPrefix = ExportGroupMembersStep.ARCHIVE_KEY_PREFIX)
          },
          step(markDisabled) { MarkGroupDisabledStep.Args(input.groupName.trim()) },
          step(deleteAdGroup) {
            DeleteAdGroupStep.Args(
                input.groupName.trim(), ctx.outputOrNull(exportMembers.outputKey))
          },
          step(finalizeArchive) {
            FinalizeGroupArchiveStep.Args(
                input.groupName.trim(),
                input.reason.trim(),
                ctx.outputOrNull(deleteAdGroup.outputKey)?.objectGuid)
          },
      )

  override fun validateInput(input: GroupArchiveInput): List<String> = buildList {
    if (input.groupName.isBlank()) add("groupName is required")
    val reason = input.reason.trim().length
    if (reason < MIN_REASON || reason > MAX_REASON) {
      add("reason must be between $MIN_REASON and $MAX_REASON characters")
    }
  }

  companion object {
    const val ID = "group.archive"
    const val MIN_REASON = 10
    const val MAX_REASON = 200
  }
}

/**
 * The safety gate: nothing is changed unless the group is in the main group OU, is not protected,
 * has no nested groups and nothing in the portal still references it.
 */
internal class CheckGroupArchivableStep(
    private val activeDirectory: IActiveDirectoryService,
    private val references: GroupReferenceChecker,
    private val directoryDispatcher: CoroutineDispatcher,
) : Step<CheckGroupArchivableStep.Args, String> {

  data class Args(val groupName: String)

  private class Assessment(val blocked: List<String>, val warnings: List<String>)

  override val id = "group.check-archivable"
  override val displayName = "Check the group can be archived"
  override val reversibility = Reversibility.REVERSIBLE
  override val outputSerializer: KSerializer<String> = String.serializer()

  override suspend fun check(ctx: StepContext, args: Args): StepCheck<String> =
      StepCheck.WillRun("Verify the group can be archived")

  /** Planning does the full assessment; when executing, [run] is the gate and this stays cheap. */
  override suspend fun validate(ctx: StepContext, args: Args): Validation {
    if (ctx.mode != StepMode.PREFLIGHT) return Validation.Ok
    val result = assess(args.groupName.trim())
    return when {
      result.blocked.isNotEmpty() -> Validation.Blocked(result.blocked)
      result.warnings.isNotEmpty() -> Validation.Warnings(result.warnings)
      else -> Validation.Ok
    }
  }

  override suspend fun run(ctx: StepContext, args: Args): String {
    val result = assess(args.groupName.trim())
    if (result.blocked.isNotEmpty()) {
      throw PermanentStepException(result.blocked.joinToString("; "))
    }
    return "The group can be archived"
  }

  override suspend fun rollback(ctx: StepContext, args: Args, output: String) = Unit

  override fun summarize(output: String) = output

  private suspend fun assess(name: String): Assessment {
    if (name.isEmpty()) return Assessment(listOf("A group name is required"), emptyList())
    val group =
        try {
          fetchGroup(name)
        } catch (e: AmbiguousGroupException) {
          return Assessment(
              listOf("${e.count} groups named '$name' exist; resolve the duplicate first"),
              emptyList())
        } ?: return Assessment(listOf("Group '$name' was not found"), emptyList())
    val blocked = mutableListOf<String>()
    val warnings = mutableListOf<String>()
    if (!DirectoryOus.isInMainGroupOu(group.distinguishedName)) {
      blocked +=
          "Group '$name' is outside the main group location and cannot be archived " +
              "through the portal"
    }
    val protected = ProtectedGroups.find(name)
    if (protected != null) blocked += "Protected group: ${protected.reason}"
    if (group.nestedGroups.isNotEmpty()) {
      blocked +=
          "The group contains ${group.nestedGroups.size} nested group(s); only groups whose " +
              "members are users can be archived"
    }
    val found = references.check(name)
    found.blocking
        .filter { protected == null || it.kind != GroupReferenceKinds.PROTECTED }
        .forEach { blocked += "${it.system}: ${it.detail}" }
    warnings += found.notes
    if (group.parentGroups.isNotEmpty()) {
      warnings +=
          "The group is a member of ${group.parentGroups.size} other group(s). Deleting it " +
              "removes those memberships, and a rollback cannot restore them"
    }
    return Assessment(blocked, warnings)
  }

  @Suppress("TooGenericExceptionCaught")
  private suspend fun fetchGroup(name: String): ADGroup? =
      try {
        withContext(directoryDispatcher) { activeDirectory.getGroupStrict(name) }
      } catch (e: CancellationException) {
        throw e
      } catch (e: GroupNotFoundException) {
        null
      } catch (e: AmbiguousGroupException) {
        throw e
      } catch (e: Exception) {
        throw TransientStepException("Could not read the group from the directory", e)
      }
}

/** Last step: records who archived the group, why and in which run. The run is not undone after. */
internal class FinalizeGroupArchiveStep(private val archive: IGroupArchiveRepository) :
    Step<FinalizeGroupArchiveStep.Args, Unit> {

  data class Args(val groupName: String, val reason: String, val objectGuid: String?)

  override val id = "group.finalize-archive"
  override val displayName = "Record the group as archived"
  override val reversibility = Reversibility.IRREVERSIBLE
  override val outputSerializer: KSerializer<Unit> = Unit.serializer()

  override suspend fun check(ctx: StepContext, args: Args): StepCheck<Unit> {
    if (ctx.mode == StepMode.PREFLIGHT) return StepCheck.WillRun("Record the group as archived")
    return if (archive.statusOf(args.groupName.trim()) == GROUP_STATUS_ARCHIVED) {
      StepCheck.NoOp("The group is already recorded as archived")
    } else {
      StepCheck.WillRun("Record the group as archived")
    }
  }

  override suspend fun run(ctx: StepContext, args: Args) {
    val done =
        archive.finalizeArchived(
            groupName = args.groupName.trim(),
            archivedBy = ctx.actor,
            reason = args.reason.trim(),
            runId = ctx.runId,
            objectGuid = args.objectGuid)
    if (!done) throw PermanentStepException("The group has no portal record to mark as archived")
  }

  /** The archive is final once recorded; nothing to undo. */
  override suspend fun rollback(ctx: StepContext, args: Args, output: Unit) = Unit

  override fun summarize(output: Unit) = "Group recorded as archived"
}

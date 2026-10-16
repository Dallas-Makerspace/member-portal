package org.dallasmakerspace.workflows.groups

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import org.dallasmakerspace.activedirectory.ADGroup
import org.dallasmakerspace.activedirectory.GroupNotFoundException
import org.dallasmakerspace.activedirectory.IActiveDirectoryService
import org.dallasmakerspace.members.GROUP_STATUS_ARCHIVED
import org.dallasmakerspace.members.GROUP_STATUS_DISABLED
import org.dallasmakerspace.members.IGroupArchiveRepository
import org.dallasmakerspace.members.IGroupHistoryRepository
import org.dallasmakerspace.workflows.PermanentStepException
import org.dallasmakerspace.workflows.PublicStep
import org.dallasmakerspace.workflows.Reversibility
import org.dallasmakerspace.workflows.StepCheck
import org.dallasmakerspace.workflows.StepContext
import org.dallasmakerspace.workflows.TransientStepException
import org.dallasmakerspace.workflows.Validation

/**
 * Portal-side only: marks the group's row DISABLED (creating the row from the directory's DN and
 * GUID if there is none yet). Rollback marks it ACTIVE again.
 */
@Singleton
class MarkGroupDisabledStep
internal constructor(
    private val activeDirectory: IActiveDirectoryService,
    private val groupHistory: IGroupHistoryRepository,
    private val archive: IGroupArchiveRepository,
    private val directoryDispatcher: CoroutineDispatcher,
) : PublicStep<MarkGroupDisabledStep.Args, Unit> {

  @Inject
  constructor(
      activeDirectory: IActiveDirectoryService,
      groupHistory: IGroupHistoryRepository,
      archive: IGroupArchiveRepository,
  ) : this(activeDirectory, groupHistory, archive, Dispatchers.IO)

  data class Args(val groupName: String)

  override val id = "group.mark-disabled"
  override val displayName = "Mark group disabled"
  override val reversibility = Reversibility.REVERSIBLE
  override val outputSerializer: KSerializer<Unit> = Unit.serializer()

  override suspend fun check(ctx: StepContext, args: Args): StepCheck<Unit> =
      if (archive.statusOf(args.groupName.trim()) == GROUP_STATUS_DISABLED) {
        StepCheck.NoOp("The group is already disabled in the portal")
      } else {
        StepCheck.WillRun("Mark the group disabled in the portal")
      }

  override suspend fun validate(ctx: StepContext, args: Args): Validation =
      when {
        args.groupName.isBlank() -> Validation.Blocked(listOf("A group name is required"))
        archive.statusOf(args.groupName.trim()) == GROUP_STATUS_ARCHIVED ->
            Validation.Warnings(
                listOf(
                    "A group with this name was archived earlier; a new portal record for the " +
                        "same name works only after the groups migration stage 4 is applied"))
        else -> Validation.Ok
      }

  override suspend fun run(ctx: StepContext, args: Args) {
    val name = args.groupName.trim()
    val group =
        fetchGroup(name) ?: throw PermanentStepException("Group '$name' was not found")
    groupHistory.resolveGroup(group.cn, group.distinguishedName, group.objectGuid)
    if (!archive.markDisabled(name)) {
      throw TransientStepException("Could not mark the group disabled in the portal")
    }
  }

  override suspend fun rollback(ctx: StepContext, args: Args, output: Unit) {
    archive.markActive(args.groupName.trim())
  }

  override fun summarize(output: Unit) = "Group marked disabled"

  @Suppress("TooGenericExceptionCaught")
  private suspend fun fetchGroup(name: String): ADGroup? =
      try {
        withContext(directoryDispatcher) { activeDirectory.getGroup(name) }
      } catch (e: CancellationException) {
        throw e
      } catch (e: GroupNotFoundException) {
        null
      } catch (e: Exception) {
        throw TransientStepException("Could not read the group from the directory", e)
      }
}

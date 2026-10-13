package org.dallasmakerspace.workflows.groups

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.Serializable
import org.dallasmakerspace.models.workflows.WorkflowInputField
import org.dallasmakerspace.workflows.Backoff
import org.dallasmakerspace.workflows.FailurePolicy
import org.dallasmakerspace.workflows.RetryPolicy
import org.dallasmakerspace.workflows.Subject
import org.dallasmakerspace.workflows.Workflow

@Serializable data class GroupExportMembersInput(val groupName: String)

/** Read-only: saves a snapshot of a group's members to the object store. */
@Singleton
class GroupExportMembersWorkflow
@Inject
constructor(private val exportMembers: ExportGroupMembersStep) :
    Workflow<GroupExportMembersInput>(
        id = ID,
        version = 1,
        displayName = "Export group members",
        description =
            "Saves a snapshot of the group's current members to the object store. " +
                "Changes nothing in the directory or the portal.",
        permission = "MANAGE_GROUPS",
        inputSerializer = GroupExportMembersInput.serializer(),
        inputFields = listOf(WorkflowInputField("groupName", "Group name")),
        onFailure = FailurePolicy.PAUSE,
        retry =
            RetryPolicy(
                maxAttempts = 3,
                backoff = Backoff.Exponential(initial = 5.seconds, max = 2.minutes)),
    ) {

  override val notes =
      listOf(
          "The export contains member names and is stored in the object store.",
          "Members of nested groups are not expanded.",
      )

  override fun subjects(input: GroupExportMembersInput) = listOf(Subject("group", input.groupName))

  override fun steps() =
      listOf(step(exportMembers) { ExportGroupMembersStep.Args(input.groupName.trim()) })

  override fun validateInput(input: GroupExportMembersInput) =
      if (input.groupName.isBlank()) listOf("groupName is required") else emptyList()

  companion object {
    const val ID = "group.export-members"
  }
}

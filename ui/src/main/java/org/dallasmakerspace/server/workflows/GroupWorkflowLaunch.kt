package org.dallasmakerspace.server.workflows

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Values the group page hands to the workflow launch button. */
object GroupWorkflowLaunch {
  const val EXPORT_MEMBERS_ID = "group.export-members"

  /** Matches the service's subject normalization (trimmed, lowercased). */
  fun subject(groupName: String) = "group:" + groupName.trim().lowercase()

  /** Escaped JSON for the button's data-input attribute. */
  fun exportMembersInput(groupName: String): String =
      buildJsonObject { put("groupName", groupName) }.toString()
}

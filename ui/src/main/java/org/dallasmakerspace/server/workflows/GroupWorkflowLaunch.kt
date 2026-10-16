package org.dallasmakerspace.server.workflows

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.dallasmakerspace.models.DirectoryOus
import org.dallasmakerspace.server.auth.Authz
import org.dallasmakerspace.server.auth.Permission

/** Values the group page hands to the workflow launch button. */
object GroupWorkflowLaunch {
  const val ARCHIVE_ID = "group.archive"

  /** Matches the service's subject normalization (trimmed, lowercased). */
  fun subject(groupName: String) = "group:" + groupName.trim().lowercase()

  /** Escaped JSON for the button's data-input attribute; the reason is asked for in the dialog. */
  fun archiveInput(groupName: String): String =
      buildJsonObject { put("groupName", groupName) }.toString()

  /**
   * Whether to offer the Archive button (the page also needs the workflows flag): main group OU
   * only, and a listed group admin (Domain Admins included) or an Infrastructure member. The
   * service re-checks all of this.
   */
  fun canArchive(distinguishedName: String?, isGroupAdmin: Boolean, authz: Authz): Boolean =
      DirectoryOus.isInMainGroupOu(distinguishedName) &&
          (isGroupAdmin || authz.can(Permission.ARCHIVE_ANY_GROUP.name))
}

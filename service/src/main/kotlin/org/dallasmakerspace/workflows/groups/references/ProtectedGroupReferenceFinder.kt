package org.dallasmakerspace.workflows.groups.references

import javax.inject.Inject
import javax.inject.Singleton
import org.dallasmakerspace.models.ProtectedGroups

/** Groups the code depends on by exact name (roles, committee chairs and teachers, voting). */
@Singleton
class ProtectedGroupReferenceFinder @Inject constructor() : GroupReferenceFinder {
  override val system = "Portal code"

  override val notes =
      listOf(
          "Committee group-name prefixes are not protected: only the exact chair, teacher, " +
              "course-editor, role and voting group names are.")

  override suspend fun find(groupName: String): List<GroupReference> {
    val protected = ProtectedGroups.find(groupName) ?: return emptyList()
    return listOf(GroupReference(system, GroupReferenceKinds.PROTECTED, 1, protected.reason))
  }
}

package org.dallasmakerspace.workflows.groups.references

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.dallasmakerspace.activedirectory.GroupNotFoundException
import org.dallasmakerspace.activedirectory.IActiveDirectoryService

/**
 * Directory objects whose managedBy names the group: deleting the group would leave them pointing
 * at nothing. Counts only. A group that cannot be found is not this finder's concern; a duplicate
 * name or a directory failure throws, which the checker turns into a blocking reference.
 */
@Singleton
class ManagedObjectsReferenceFinder
internal constructor(
    private val activeDirectory: IActiveDirectoryService,
    private val directoryDispatcher: CoroutineDispatcher,
) : GroupReferenceFinder {
  @Inject
  constructor(
      activeDirectory: IActiveDirectoryService
  ) : this(activeDirectory, Dispatchers.IO)

  override val system = "Directory"

  override suspend fun find(groupName: String): List<GroupReference> {
    val counts =
        try {
          withContext(directoryDispatcher) { activeDirectory.countManagedObjects(groupName) }
        } catch (e: GroupNotFoundException) {
          return emptyList()
        }
    if (counts.total == 0) return emptyList()
    val parts =
        listOf(
                counts.groups to "group(s)",
                counts.users to "user(s)",
                counts.computers to "computer(s)",
                counts.others to "other object(s)")
            .filter { it.first > 0 }
            .joinToString(", ") { "${it.first} ${it.second}" }
    return listOf(
        GroupReference(
            system,
            GroupReferenceKinds.MANAGED_OBJECTS,
            counts.total,
            "$parts managed by this group"))
  }
}

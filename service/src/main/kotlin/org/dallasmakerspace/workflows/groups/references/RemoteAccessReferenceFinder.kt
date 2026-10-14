package org.dallasmakerspace.workflows.groups.references

import javax.inject.Inject
import javax.inject.Singleton
import org.dallasmakerspace.config.ConfigOverrideService
import org.dallasmakerspace.remoteaccess.RemoteAccessCategory

/** Remote-access categories gate access on an AD group named in a site config key. */
@Singleton
class RemoteAccessReferenceFinder
internal constructor(
    private val reader: ConfigValueReader,
    private val categories: List<RemoteAccessCategory>,
) : GroupReferenceFinder {
  @Inject
  constructor(
      configOverrideService: ConfigOverrideService
  ) : this(configOverrideService.asReader(), RemoteAccessCategory.entries)

  override val system = "Remote access"

  override suspend fun find(groupName: String): List<GroupReference> {
    val gated =
        categories.filter { sameGroup(reader.read(it.adGroupConfigKey) as? String, groupName) }
    if (gated.isEmpty()) return emptyList()
    val detail = "Required for access to: ${summarize(gated.map { it.displayName })}"
    return listOf(GroupReference(system, GroupReferenceKinds.REMOTE_ACCESS, gated.size, detail))
  }
}

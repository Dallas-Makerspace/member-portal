package org.dallasmakerspace.workflows.groups.references

import javax.inject.Inject
import javax.inject.Singleton
import org.dallasmakerspace.config.ConfigKey
import org.dallasmakerspace.config.ConfigOverrideService
import org.dallasmakerspace.config.ConfigRegistry
import org.dallasmakerspace.config.ConfigValueType
import org.dallasmakerspace.config.FeatureFlagValue
import org.dallasmakerspace.remoteaccess.RemoteAccessCategory

/** Reads a config key's stored value, ignoring the cache and the infra-only mask. */
internal fun interface ConfigValueReader {
  suspend fun read(key: ConfigKey<*>): Any?
}

internal fun ConfigOverrideService.asReader() = ConfigValueReader { key ->
  @Suppress("UNCHECKED_CAST") getFresh(key as ConfigKey<Any?>)
}

private val REMOTE_ACCESS_KEYS =
    RemoteAccessCategory.entries.map { it.adGroupConfigKey.key }.toSet()

/**
 * Site config naming the group: feature flags listing it in `enabledGroupNames`, and string values
 * equal to it. The remote-access group keys are left to [RemoteAccessReferenceFinder] so they are
 * not counted twice.
 */
@Singleton
class ConfigReferenceFinder
internal constructor(
    private val reader: ConfigValueReader,
    private val keys: List<ConfigKey<*>>,
) : GroupReferenceFinder {
  @Inject
  constructor(
      configOverrideService: ConfigOverrideService
  ) : this(configOverrideService.asReader(), ConfigRegistry.ALL)

  override val system = "Site config"

  override suspend fun find(groupName: String): List<GroupReference> {
    val flags = mutableListOf<String>()
    val values = mutableListOf<String>()
    for (key in keys) {
      if (key.key in REMOTE_ACCESS_KEYS) continue
      when (key.type) {
        is ConfigValueType.FeatureFlagType -> {
          val flag = reader.read(key) as? FeatureFlagValue ?: continue
          if (flag.enabledGroupNames.any { sameGroup(it, groupName) }) flags += key.key
        }
        is ConfigValueType.StringType ->
            if (sameGroup(reader.read(key) as? String, groupName)) values += key.key
        else -> Unit
      }
    }
    return buildList {
      if (flags.isNotEmpty()) {
        add(
            GroupReference(
                system,
                GroupReferenceKinds.FEATURE_FLAG,
                flags.size,
                "Listed in enabledGroupNames of: ${summarize(flags)}"))
      }
      if (values.isNotEmpty()) {
        add(
            GroupReference(
                system,
                GroupReferenceKinds.CONFIG_VALUE,
                values.size,
                "Set as the value of: ${summarize(values)}"))
      }
    }
  }
}

package org.dallasmakerspace.config

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.dallasmakerspace.activedirectory.IActiveDirectoryService
import org.dallasmakerspace.core.LoggerFactory

/**
 * Resolves [ConfigValueType.FeatureFlagType] flags for a specific member, pulling their AD group
 * membership so the UI (which can't do its own AD lookups) can ask "is this feature on for user X"
 * in one call.
 */
@Singleton
class FeatureFlagService
@Inject
constructor(
    private val configOverrideService: ConfigOverrideService,
    private val activeDirectoryService: IActiveDirectoryService,
    loggerFactory: LoggerFactory,
) {
  private val log = loggerFactory.create(javaClass)

  @Suppress("TooGenericExceptionCaught")
  suspend fun isEnabledForUsername(
      configKey: ConfigKey<FeatureFlagValue>,
      username: String
  ): Boolean {
    val groupNames =
        withContext(Dispatchers.IO) {
          try {
            activeDirectoryService.getMembersByUsernameList(listOf(username))[username]
                ?.groups
                ?.map { it.cn } ?: emptyList()
          } catch (e: Exception) {
            log.warn("Failed to resolve AD groups for $username while checking feature flag", e)
            emptyList()
          }
        }
    return configOverrideService.isFeatureEnabledFor(configKey, username, groupNames)
  }
}

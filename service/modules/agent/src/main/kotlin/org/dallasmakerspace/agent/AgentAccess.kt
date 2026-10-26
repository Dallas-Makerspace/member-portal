package org.dallasmakerspace.agent

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.dallasmakerspace.activedirectory.IActiveDirectoryService
import org.dallasmakerspace.config.ConfigOverrideService
import org.dallasmakerspace.core.LoggerFactory

/**
 * Per-member checks for the assistants. The service otherwise trusts the UI's X-Username, so these
 * re-check the bot's flag and audience against the member's AD groups.
 */
@Singleton
class AgentAccess
@Inject
constructor(
    private val activeDirectoryService: IActiveDirectoryService,
    private val configOverrideService: ConfigOverrideService,
    loggerFactory: LoggerFactory,
) {
  private val log = loggerFactory.create(javaClass)

  @Suppress("TooGenericExceptionCaught")
  suspend fun groupsOf(username: String): List<String> =
      withContext(Dispatchers.IO) {
        try {
          activeDirectoryService.getMembersByUsernameList(listOf(username))[username]
              ?.groups
              ?.map { it.cn } ?: emptyList()
        } catch (e: Exception) {
          log.warn("Failed to resolve AD groups for $username", e)
          emptyList()
        }
      }

  suspend fun canUse(bot: BotDefinition, username: String, groups: List<String>): Boolean =
      configOverrideService.isFeatureEnabledFor(bot.featureFlag, username, groups) &&
          inAudience(bot.audience, groups)

  private suspend fun inAudience(audience: BotAudience, groups: List<String>): Boolean =
      when (audience) {
        BotAudience.AllMembers -> true
        is BotAudience.AdGroup -> {
          val group = configOverrideService.get(audience.groupKey).trim()
          group.isNotEmpty() && groups.any { it.equals(group, ignoreCase = true) }
        }
        is BotAudience.AdGroupSuffix ->
            groups.any { it.endsWith(audience.suffix, ignoreCase = true) }
      }
}

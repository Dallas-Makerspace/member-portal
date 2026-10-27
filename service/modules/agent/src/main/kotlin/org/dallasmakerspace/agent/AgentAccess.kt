package org.dallasmakerspace.agent

import java.util.concurrent.ConcurrentHashMap
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

  private data class CachedGroups(val groups: List<String>, val loadedAt: Long)

  private val cache = ConcurrentHashMap<String, CachedGroups>()

  /** AD groups of [username], remembered for [GROUP_TTL_MILLIS] so clicking around isn't slow. */
  @Suppress("TooGenericExceptionCaught")
  suspend fun groupsOf(username: String): List<String> {
    val cached = cache[username]
    if (cached != null && System.currentTimeMillis() - cached.loadedAt < GROUP_TTL_MILLIS) return cached.groups
    return withContext(Dispatchers.IO) {
      try {
        val groups =
            activeDirectoryService.getMembersByUsernameList(listOf(username))[username]
                ?.groups
                ?.map { it.cn } ?: emptyList()
        cache[username] = CachedGroups(groups, System.currentTimeMillis())
        groups
      } catch (e: Exception) {
        log.warn("Failed to resolve AD groups for $username", e)
        emptyList()
      }
    }
  }

  private companion object {
    const val GROUP_TTL_MILLIS = 60_000L
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

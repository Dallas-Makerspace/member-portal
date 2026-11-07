package org.dallasmakerspace.agenttools.activity

import javax.inject.Inject
import javax.inject.Singleton
import org.dallasmakerspace.agent.AgentAccess
import org.dallasmakerspace.config.ConfigOverrideService
import org.dallasmakerspace.config.ConfigRegistry

/**
 * Which areas a member may ask about: none unless the feature flag is on for them, then all areas
 * for the configured groups (Infrastructure and officers), otherwise only the areas of a committee
 * they chair.
 */
@Singleton
class ActivityAccess
@Inject
constructor(
    private val configOverrideService: ConfigOverrideService,
    private val agentAccess: AgentAccess,
) {

  suspend fun areasFor(groups: List<String>): List<Area> {
    val all =
        configOverrideService.get(ConfigRegistry.AGENT_ACTIVITY_TOOLS_AD_GROUPS)
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    if (groups.any { g -> all.any { it.equals(g, ignoreCase = true) } }) return Areas.ALL
    return Areas.ALL.filter { area ->
      area.chairGroup?.let { chair -> groups.any { it.equals(chair, ignoreCase = true) } } == true
    }
  }

  suspend fun areasOf(username: String): List<Area> {
    val groups = agentAccess.groupsOf(username)
    val enabled =
        configOverrideService.isFeatureEnabledFor(
            ConfigRegistry.AGENT_ACTIVITY_TOOLS_ENABLED, username, groups)
    return if (enabled) areasFor(groups) else emptyList()
  }
}

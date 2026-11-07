package org.dallasmakerspace.agenttools.activity

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import org.dallasmakerspace.models.Area
import org.dallasmakerspace.models.Areas
import org.dallasmakerspace.agent.AgentAccess
import org.dallasmakerspace.config.ConfigOverrideService
import org.dallasmakerspace.config.ConfigRegistry

/**
 * Which areas a member may ask about: all of them for the configured groups (Infrastructure and
 * officers), otherwise only the areas of a committee they chair.
 *
 * The answer, including "none" for the many members with no access, is remembered for
 * [TTL_MILLIS] and used both to offer the tools and to check each call, so this costs a directory
 * lookup at most once per member per period. A group change can therefore take that long to apply.
 */
@Singleton
class ActivityAccess
@Inject
constructor(
    private val configOverrideService: ConfigOverrideService,
    private val agentAccess: AgentAccess,
) {
  private class Decision(val areas: List<Area>, val decidedAt: Long)

  private val decisions = ConcurrentHashMap<String, Decision>()

  internal var clock: () -> Long = System::currentTimeMillis

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
    val now = clock()
    decisions[username]?.takeIf { now - it.decidedAt < TTL_MILLIS }?.let { return it.areas }
    val areas = areasFor(agentAccess.groupsOf(username))
    decisions[username] = Decision(areas, now)
    return areas
  }

  suspend fun isOffered(username: String): Boolean = areasOf(username).isNotEmpty()

  companion object {
    const val TTL_MILLIS = 15 * 60_000L
  }
}

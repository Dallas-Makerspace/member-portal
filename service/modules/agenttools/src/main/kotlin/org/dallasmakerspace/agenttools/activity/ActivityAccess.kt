package org.dallasmakerspace.agenttools.activity

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import org.dallasmakerspace.agent.AgentAccess
import org.dallasmakerspace.config.ConfigOverrideService
import org.dallasmakerspace.config.ConfigRegistry

/**
 * Which areas a member may ask about: all of them for the configured groups (Infrastructure and
 * officers), otherwise only the areas of a committee they chair.
 *
 * [areasOf] is what every tool call is checked against. [isOffered] only decides whether the tools
 * are shown to the model, so it may lag a group change by [OFFER_TTL_MILLIS]; this keeps the
 * directory lookup off most turns, including for the many members who have no access.
 */
@Singleton
class ActivityAccess
@Inject
constructor(
    private val configOverrideService: ConfigOverrideService,
    private val agentAccess: AgentAccess,
) {
  private class Offer(val offered: Boolean, val decidedAt: Long)

  private val offers = ConcurrentHashMap<String, Offer>()

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

  suspend fun areasOf(username: String): List<Area> = areasFor(agentAccess.groupsOf(username))

  suspend fun isOffered(username: String): Boolean {
    val now = clock()
    offers[username]?.takeIf { now - it.decidedAt < OFFER_TTL_MILLIS }?.let { return it.offered }
    val offered = areasOf(username).isNotEmpty()
    offers[username] = Offer(offered, now)
    return offered
  }

  companion object {
    const val OFFER_TTL_MILLIS = 5 * 60_000L
  }
}

package org.dallasmakerspace.dashboards

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.dallasmakerspace.activedirectory.IActiveDirectoryService
import org.dallasmakerspace.config.ConfigOverrideService
import org.dallasmakerspace.config.ConfigRegistry
import org.dallasmakerspace.config.FeatureFlagService
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.members.MemberService
import org.dallasmakerspace.models.Committee
import org.dallasmakerspace.models.Committees

/**
 * Who may act for a committee: its chairs (members of the committee's chair AD group) and the
 * Infrastructure group. Every check fails closed.
 */
@Singleton
class CommitteeAccess
@Inject
constructor(
    private val activeDirectoryService: IActiveDirectoryService,
    private val memberService: MemberService,
    private val featureFlagService: FeatureFlagService,
    private val configOverrideService: ConfigOverrideService,
    loggerFactory: LoggerFactory,
) {
  private val log = loggerFactory.create(javaClass)

  suspend fun isChairOrInfra(actor: String, committee: Committee): Boolean =
      isChair(actor, committee) || isInfra(actor)

  suspend fun isInfra(actor: String): Boolean =
      withContext(Dispatchers.IO) { memberService.isUserInInfraGroup(actor) }

  /** A directory error means "not a chair". */
  @Suppress("TooGenericExceptionCaught")
  suspend fun isChair(actor: String, committee: Committee): Boolean {
    val chairGroup = committee.chairGroupName ?: return false
    return withContext(Dispatchers.IO) {
      try {
        activeDirectoryService.getMemberByUsername(actor).groups.any {
          it.cn.equals(chairGroup, ignoreCase = true)
        }
      } catch (e: Exception) {
        log.warn("Could not check chair status of $actor for ${committee.name}; denying", e)
        false
      }
    }
  }

  /** Whether [committee] has a TV dashboard at all (the committees list in site config). */
  suspend fun hasDashboard(committee: Committee): Boolean =
      dashboardCommittees(configOverrideService.get(ConfigRegistry.COMMITTEE_DASHBOARD_COMMITTEES))
          .let { names ->
            committee.name.lowercase() in names || committee.slug in names
          }

  /** Whether [actor] may edit [committee]'s dashboard: flag on for them, and chair or admin. */
  suspend fun canEditDashboard(actor: String, committee: Committee): Boolean =
      hasDashboard(committee) &&
          featureFlagService.isEnabledForUsername(
              ConfigRegistry.COMMITTEE_DASHBOARD_ENABLED, actor) &&
          isChairOrInfra(actor, committee)

  fun committee(id: Int): Committee? = Committees.findById(id)?.takeIf { it.isActive }

  companion object {
    fun dashboardCommittees(configured: String): Set<String> =
        configured.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
  }
}

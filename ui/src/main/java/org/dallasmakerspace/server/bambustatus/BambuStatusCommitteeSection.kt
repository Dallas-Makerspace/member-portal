package org.dallasmakerspace.server.bambustatus

import javax.inject.Inject
import org.dallasmakerspace.models.Committee
import org.dallasmakerspace.server.committees.CommitteePageSection
import org.dallasmakerspace.server.committees.CommitteePageSectionProvider
import org.dallasmakerspace.server.memberservice.MemberServiceClient

private const val THREE_D_FAB_SLUG = "3d-fab"

/**
 * Contributes the "N available / M printing / K offline" Bambu summary badge to 3D Fab's committee
 * page. The only place in this feature that mentions "3D Fab" by name - everything else (the
 * committee page itself, [org.dallasmakerspace.server.routes.CommitteeDetailHandler]) is generic
 * over [CommitteePageSectionProvider].
 */
class BambuStatusCommitteeSection
@Inject
constructor(private val memberServiceClient: MemberServiceClient) : CommitteePageSectionProvider {

  override fun appliesTo(committee: Committee): Boolean = committee.slug == THREE_D_FAB_SLUG

  override suspend fun renderSection(
      committee: Committee,
      sessionId: String?,
      username: String?,
  ): CommitteePageSection? {
    if (username == null) return null
    val enabled = memberServiceClient.isFeatureEnabled("bambu-status.enabled", username, sessionId)
    if (!enabled) return null

    val summary = memberServiceClient.getBambuStatusSummary(sessionId) ?: return null
    return CommitteePageSection(
        fragmentName = "fragments/bambu-status-summary", model = mapOf("summary" to summary))
  }
}

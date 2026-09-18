package org.dallasmakerspace.server.committees

import org.dallasmakerspace.models.Committee

/**
 * One contributed chunk of content on a committee's detail page (`/committees/{slug}`).
 *
 * Lets a feature (e.g. Bambu printer status for 3D Fab) attach itself to a specific committee's
 * page without the generic committee-page code knowing anything about it - implementations are
 * discovered via Dagger multibinding (`@Binds @IntoSet`) and [CommitteeDetailHandler] just asks
 * every provider whether it applies to the committee being rendered.
 */
interface CommitteePageSectionProvider {
  /** Whether this provider has content to show on [committee]'s page. */
  fun appliesTo(committee: Committee): Boolean

  /**
   * Builds the section to render, or null to contribute nothing this time (e.g. the underlying
   * feature is behind a flag that's off for the current member). Called only when [appliesTo]
   * returned true.
   */
  suspend fun renderSection(
      committee: Committee,
      sessionId: String?,
      username: String?,
  ): CommitteePageSection?
}

/**
 * [fragmentName] is a template path (e.g. "fragments/bambu-status-summary") whose "content"
 * fragment is inserted into committee-detail.html with [model]'s entries available in scope as
 * `${sectionModel}` - see committee-detail.html's `th:each` over `${sections}`.
 */
data class CommitteePageSection(val fragmentName: String, val model: Map<String, Any?>)

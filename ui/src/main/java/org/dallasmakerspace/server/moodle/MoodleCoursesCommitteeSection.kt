package org.dallasmakerspace.server.moodle

import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.dallasmakerspace.models.Committee
import org.dallasmakerspace.models.displayAvatarUrl
import org.dallasmakerspace.models.normalizeAvatarUrl
import org.dallasmakerspace.server.committees.CommitteePageSection
import org.dallasmakerspace.server.committees.CommitteePageSectionProvider
import org.dallasmakerspace.server.memberservice.MemberServiceClient
import org.dallasmakerspace.server.memberservice.MemberServiceException
import org.dallasmakerspace.server.models.getSlugFromName

private const val COLLAPSED_COUNT = 3
private const val EDITORS_GROUP_SUFFIX = "Online Course Editors"

/**
 * "Online Courses" card on committee pages. Renders only a placeholder so the page never waits on
 * Moodle; the card itself is loaded from [CommitteeCoursesHandler].
 */
class MoodleCoursesCommitteeSection @Inject constructor() : CommitteePageSectionProvider {

  // Courses map to committees by Moodle category, so any committee may have some.
  override fun appliesTo(committee: Committee): Boolean = true

  override suspend fun renderSection(
      committee: Committee,
      sessionId: String?,
      username: String?,
  ): CommitteePageSection? =
      username?.let {
        CommitteePageSection(
            fragmentName = "fragments/moodle-courses-placeholder",
            model = mapOf("committeeSlug" to committee.slug))
      }
}

/** Builds the card model for a committee, or null when the flag is off or there are no courses. */
class MoodleCommitteeCoursesLoader
@Inject
constructor(private val memberServiceClient: MemberServiceClient) {

  suspend fun load(
      committee: Committee,
      sessionId: String?,
      username: String,
      canAdminGroups: Boolean = false,
  ): Map<String, Any?>? =
      coroutineScope {
        if (!memberServiceClient.isFeatureEnabled(MOODLE_FEATURE_FLAG, username, sessionId)) {
          return@coroutineScope null
        }
        val memberCoursesDeferred = async {
          memberServiceClient.getMoodleMemberCourses(sessionId, username)
        }
        val editorsDeferred = async { loadEditors(committee, sessionId, username, canAdminGroups) }
        val catalog = memberServiceClient.getMoodleCatalog(sessionId, committee.slug)
        val courses = MoodleCourses.parse(catalog, memberCoursesDeferred.await()).filter { it.joinable }
        if (courses.isEmpty()) return@coroutineScope null
        val model =
            mutableMapOf<String, Any?>(
                "courses" to courses.take(COLLAPSED_COUNT),
                "moreCourses" to courses.drop(COLLAPSED_COUNT),
                "totalCount" to courses.size,
                "statsUrl" to "/reports/online-courses/overview?committee=${committee.slug}",
                "editors" to emptyList<Map<String, Any?>>(),
                "editorsGroupUrl" to null,
                "canManageEditors" to false,
            )
        editorsDeferred.await()?.let { model.putAll(it) }
        model
      }

  /**
   * The committee's "<prefix> Online Course Editors" AD group, found by naming convention. Its
   * members are the course editors; the chair manages the group, so they get a link to it.
   */
  private suspend fun loadEditors(
      committee: Committee,
      sessionId: String?,
      username: String,
      canAdminGroups: Boolean,
  ): Map<String, Any?>? {
    val names =
        (committee.groupPrefixes + committee.name).distinct().map { "$it $EDITORS_GROUP_SUFFIX" }
    val group =
        try {
          val found = memberServiceClient.getMultipleGroups(names.map { getSlugFromName(it) }, sessionId)
          names.firstNotNullOfOrNull { n -> found.firstOrNull { it.name.equals(n, ignoreCase = true) } }
        } catch (ignored: MemberServiceException) {
          null // the card renders fine without editors
        } ?: return null
    val slug = getSlugFromName(group.name)
    return mapOf(
        "editors" to
            group.members
                .filter { it.enabled }
                .map {
                  mapOf(
                      "username" to it.username,
                      "displayName" to (it.displayName ?: it.username),
                      "avatarUrl" to normalizeAvatarUrl(it.displayAvatarUrl(), size = 64),
                  )
                },
        "editorsGroupUrl" to "/groups/$slug",
        "canManageEditors" to (canAdminGroups || memberServiceClient.isGroupAdmin(username, slug, sessionId)),
    )
  }
}
